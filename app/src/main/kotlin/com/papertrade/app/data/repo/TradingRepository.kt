package com.papertrade.app.data.repo

import com.papertrade.app.data.db.*
import com.papertrade.app.data.market.InstrumentInfo
import com.papertrade.app.data.market.MarketQuote
import com.papertrade.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class SimulationExport(
    val version: Int = 1, val exportedAt: Long, val wallet: WalletEntity, val orders: List<OrderEntity>, val fills: List<FillEntity>,
    val positions: List<PositionEntity>, val ledger: List<LedgerEntity>, val instruments: List<InstrumentEntity>,
    val watchlists: List<WatchlistEntity>, val watchlistItems: List<WatchlistItemEntity>,
    /** Hand-entered mutual funds. Null in exports made before they existed; importing those leaves funds untouched. */
    val mutualFunds: List<MutualFundEntity>? = null,
)

/**
 * Bridges the pure engine to Room. Every engine call is serialised by [mutex]: load state -> run engine -> persist delta in ONE
 * Room transaction. Fill/ledger primary keys make a replayed write a no-op, so a fill can never be applied twice.
 */
class TradingRepository(
    private val db: AppDatabase,
    private val settings: suspend () -> AppSettings,
    private val clock: () -> Instant = { Instant.now() },
    val startingCapital: BigDecimal = BigDecimal("5000000"),
) {
    private val dao = db.trading(); private val market = db.marketCache()
    private val mutex = Mutex()
    private val instruments = ConcurrentHashMap<String, Instrument>()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    val account: Flow<AccountState> = combine(dao.walletFlow(), dao.ordersFlow(), dao.positionsFlow(), dao.fillsFlow(), dao.ledgerFlow()) { w, o, p, f, l ->
        AccountState(w?.toDomain() ?: Wallet(startingCapital, startingCapital), o.associate { it.id to it.toDomain() }, p.associate { it.instrumentId to it.toDomain() },
            f.reversed().map { it.toDomain() }, l.map { it.toDomain() })
    }

    /** Symbols that need live prices regardless of which screen is open (open orders and open positions). */
    val activeSymbols: Flow<Set<String>> = combine(dao.ordersFlow(), dao.positionsFlow()) { o, p ->
        o.filter { it.status == "OPEN" || it.status == "PARTIALLY_FILLED" }.map { it.instrumentId }.toSet() + p.filter { it.quantity != 0 }.map { it.instrumentId }
    }

    suspend fun init() = mutex.withLock {
        market.instruments().forEach { instruments[it.symbol] = it.toInfo().toCore() }
        if (dao.wallet() == null) {
            val now = clock(); val s = AccountState.initial(startingCapital, now)
            dao.persistTrade(s.wallet.toEntity(), emptyList(), emptyList(), emptyList(), s.ledger.mapIndexed { i, e -> e.toEntity(i.toLong()) })
        } else topUpOldCapital()
    }

    /**
     * One time per raise: wallets created with an old default (₹10,00,000, then ₹13,54,510) move to [startingCapital]. The
     * difference is added to cash with a ledger entry (ledger sum must equal cash), so trades, positions and net P&L stay
     * exactly as they were. Each raise has its own entry id, so an earlier top-up never blocks a later one.
     */
    private suspend fun topUpOldCapital() {
        val w = dao.wallet()?.toDomain() ?: return
        if (OLD_DEFAULTS.none { it.compareTo(w.startingCapital) == 0 } || startingCapital.compareTo(w.startingCapital) <= 0) return
        val delta = startingCapital - w.startingCapital
        val entry = LedgerEntry("capital-topup-${startingCapital.toPlainString()}", LedgerType.INITIAL_CAPITAL, delta, null, clock(), "Starting virtual capital raised to ${startingCapital.toPlainString()}")
        dao.persistTrade(w.copy(startingCapital = startingCapital, cash = w.cash + delta).toEntity(), emptyList(), emptyList(), emptyList(),
            listOf(entry.toEntity(dao.ledger().size.toLong())))
    }

    suspend fun registerInstrument(info: InstrumentInfo) {
        instruments[info.symbol] = info.toCore()
        market.upsertInstrument(info.toEntity(clock()))
    }
    fun instrument(symbol: String): Instrument? = instruments[symbol]
    fun instrumentsSnapshot(): Map<String, Instrument> = instruments.toMap()

    private suspend fun exchange() = PaperExchange(settings().toExecutionConfig(), { instruments[it] }, { "ORD" + UUID.randomUUID().toString().take(8).uppercase() }, { LocalDate.now(MarketHours.IST) })

    private suspend fun load(): AccountState = AccountState(
        dao.wallet()?.toDomain() ?: Wallet(startingCapital, startingCapital), dao.orders().associate { it.id to it.toDomain() },
        dao.positions().associate { it.instrumentId to it.toDomain() }, dao.fills().map { it.toDomain() }, dao.ledger().map { it.toDomain() })

    private suspend fun persist(old: AccountState, new: AccountState) {
        val oldFills = old.fills.mapTo(HashSet()) { it.id }; val oldLedger = old.ledger.mapTo(HashSet()) { it.id }
        dao.persistTrade(
            new.wallet.toEntity(),
            new.orders.values.filter { old.orders[it.id] != it }.map { it.toEntity() },
            new.positions.values.filter { old.positions[it.instrumentId] != it }.map { it.toEntity() },
            new.fills.filter { it.id !in oldFills }.map { it.toEntity() },
            new.ledger.withIndex().filter { it.value.id !in oldLedger }.map { it.value.toEntity(it.index.toLong()) },
        )
    }

    suspend fun place(info: InstrumentInfo, req: OrderRequest, quote: MarketQuote?): EngineResult {
        registerInstrument(info)
        return mutex.withLock {
            val ex = exchange(); val old = load(); val r = ex.place(old, req, quote?.toCore(), clock()); persist(old, r.state); r
        }
    }

    suspend fun onQuote(q: MarketQuote): EngineResult? {
        if (dao.activeCount(q.symbol) == 0) return null
        return mutex.withLock {
            val ex = exchange(); val old = load(); val r = ex.onQuote(old, q.toCore(), clock()); if (r.state != old) persist(old, r.state); r
        }
    }

    suspend fun cancel(orderId: String): EngineResult = mutex.withLock {
        val ex = exchange(); val old = load(); val r = ex.cancel(old, orderId, clock()); persist(old, r.state); r
    }

    suspend fun resetSimulation() = mutex.withLock {
        val s = AccountState.initial(startingCapital, clock())
        dao.replaceAll(s.wallet.toEntity(), emptyList(), emptyList(), emptyList(), s.ledger.mapIndexed { i, e -> e.toEntity(i.toLong()) })
        db.mutualFunds().clear()
    }

    /**
     * Brings in a delivery stock bought outside the app, at the user's own buy price. No cash moves: starting capital rises by the
     * cost instead, so net P&L (equity - starting capital) counts only the move since that price. Adding to a holding re-averages it.
     */
    suspend fun addHolding(info: InstrumentInfo, qty: Int, price: BigDecimal) {
        require(info.segment == Segment.EQUITY) { "Only stocks can be added as holdings" }
        require(qty > 0 && price.signum() > 0) { "Units and price must be more than zero" }
        registerInstrument(info)
        mutex.withLock {
            val old = load(); val pos = old.positions[info.symbol] ?: Position(info.symbol)
            val upd = PositionMath.apply(pos, Side.BUY, qty, Dec.price(price), 1, BigDecimal.ZERO).position
            val cost = Dec.money(price * BigDecimal(qty))
            persist(old, old.copy(wallet = old.wallet.copy(startingCapital = old.wallet.startingCapital + cost), positions = old.positions + (info.symbol to upd)))
        }
    }

    suspend fun valuation(state: AccountState, price: (String) -> BigDecimal?) = Portfolio.value(state, instruments.toMap(), price)

    // ---- local export / import ----
    suspend fun exportJson(): String = mutex.withLock {
        json.encodeToString(SimulationExport.serializer(), SimulationExport(
            exportedAt = clock().toEpochMilli(), wallet = dao.wallet() ?: Wallet(startingCapital, startingCapital).toEntity(), orders = dao.orders(),
            fills = dao.fills(), positions = dao.positions(), ledger = dao.ledger(), instruments = market.instruments(),
            watchlists = emptyList(), watchlistItems = emptyList(), mutualFunds = db.mutualFunds().allNow()))
    }

    /** Validates before replacing anything: version, ledger sum == cash, unique ids, fills reference known orders. */
    suspend fun importJson(text: String) = mutex.withLock {
        val x = json.decodeFromString(SimulationExport.serializer(), text)
        require(x.version == 1) { "Unsupported export version ${x.version}" }
        val ledgerSum = x.ledger.fold(BigDecimal.ZERO) { a, e -> a + BigDecimal(e.amount) }
        require(ledgerSum.compareTo(BigDecimal(x.wallet.cash)) == 0) { "Ledger total $ledgerSum does not match cash ${x.wallet.cash}" }
        require(x.fills.map { it.id }.toSet().size == x.fills.size) { "Duplicate fill ids" }
        require(x.ledger.map { it.id }.toSet().size == x.ledger.size) { "Duplicate ledger ids" }
        val orderIds = x.orders.map { it.id }.toSet()
        require(x.fills.all { it.orderId in orderIds }) { "Fill references unknown order" }
        x.instruments.forEach { market.upsertInstrument(it); instruments[it.symbol] = it.toInfo().toCore() }
        dao.replaceAll(x.wallet, x.orders, x.positions, x.fills, x.ledger)
        x.mutualFunds?.let { db.mutualFunds().replaceAll(it) }
    }

    private companion object {
        /** Starting capitals of earlier versions; a wallet still on one of them is raised to the current default. */
        val OLD_DEFAULTS = listOf(BigDecimal("1000000"), BigDecimal("1354510"))
    }
}
