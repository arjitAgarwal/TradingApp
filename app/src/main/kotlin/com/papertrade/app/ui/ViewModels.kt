package com.papertrade.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.papertrade.app.AppContainer
import com.papertrade.app.data.market.*
import com.papertrade.app.data.repo.AppSettings
import com.papertrade.app.data.repo.Watchlist
import com.papertrade.app.ui.components.UiState
import com.papertrade.core.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

fun Throwable.toUi(): UiState.Error {
    val k = (this as? ProviderException)?.kind
    return UiState.Error(message ?: "Unknown error", offline = k == ProviderException.Kind.NETWORK,
        title = if (k == ProviderException.Kind.RATE_LIMIT) "FYERS request limit reached" else null)
}

/** Activity-scoped: wallet, orders, positions, quotes, settings. Screens read these; none of them talk to a broker. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppVM(private val c: AppContainer) : ViewModel() {
    val settings = c.settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val quotes: StateFlow<Map<String, MarketQuote>> = c.hub.latest
    val provider: StateFlow<MarketDataProvider> = c.hub.provider
    val feed: StateFlow<FeedStatus> = c.hub.provider.flatMapLatest { it.feedStatus }.stateIn(viewModelScope, SharingStarted.Eagerly, FeedStatus(FeedState.NOT_CONFIGURED))
    val now: StateFlow<Instant> = flow { while (true) { emit(Instant.now()); delay(1000) } }.stateIn(viewModelScope, SharingStarted.Eagerly, Instant.now())
    val account: StateFlow<AccountState?> = c.trading.account.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val watchlists: StateFlow<List<Watchlist>> = c.watchlists.watchlists.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val valuation: StateFlow<PortfolioValuation?> = combine(account, quotes, c.hub.provider) { a, q, _ ->
        a?.let { Portfolio.value(it, c.trading.instrumentsSnapshot()) { id -> q[id]?.ltp } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val fyersAuth: StateFlow<com.papertrade.app.data.repo.LoginState> = c.fyersAuth.state
    fun fyersSetUp() = c.fyersAuth.isSetUp()
    fun fyersLoginUrl() = c.fyersAuth.loginUrl()
    fun fyersRedirectUri() = c.fyersAuth.redirectUri()
    fun fyersLoginValidUntil() = c.fyersAuth.loginValidUntil()
    val upstoxAuth: StateFlow<com.papertrade.app.data.repo.LoginState> = c.upstoxAuth.state
    fun upstoxSetUp() = c.upstoxAuth.isSetUp()
    fun upstoxLoginUrl() = c.upstoxAuth.loginUrl()
    fun upstoxRedirectUri() = c.upstoxAuth.redirectUri()
    fun upstoxLoginValidUntil() = c.upstoxAuth.loginValidUntil()
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    fun register(i: InstrumentInfo) { c.contracts[i.symbol] = i; viewModelScope.launch { c.trading.registerInstrument(i) } }
    /** Session cache first, then metadata persisted by the repository (survives restarts). */
    fun info(symbol: String): InstrumentInfo? = c.contracts[symbol] ?: c.trading.instrument(symbol)?.let { i ->
        InstrumentInfo(i.symbol, i.name, i.segment, i.multiplier, i.tickSize, i.expiry, i.strike, i.optionType, i.underlyingId)
    }
    fun subscribe(owner: String, symbols: Set<String>) = c.hub.subscribe(owner, symbols)
    fun unsubscribe(owner: String) = c.hub.unsubscribe(owner)
    fun maxAgeSec() = settings.value.maxQuoteAgeSec.toLong()

    fun placeOrder(info: InstrumentInfo, side: Side, type: OrderType, qty: Int, limit: BigDecimal?, onDone: (EngineResult) -> Unit = {}) {
        viewModelScope.launch {
            c.contracts[info.symbol] = info
            val r = c.trading.place(info, OrderRequest(info.symbol, side, type, qty, limit), quotes.value[info.symbol])
            messages.tryEmit(r.error?.let { "Order rejected: $it" } ?: "${side.name} order ${r.order?.status?.name?.lowercase()?.replace('_', ' ')}")
            onDone(r)
        }
    }
    fun cancel(orderId: String) = viewModelScope.launch { val r = c.trading.cancel(orderId); messages.tryEmit(r.error ?: "Order cancelled") }
    fun closePosition(p: Position) {
        val inst = info(p.instrumentId) ?: return
        placeOrder(inst, if (p.quantity > 0) Side.SELL else Side.BUY, OrderType.MARKET, kotlin.math.abs(p.quantity), null)
    }
    fun saveSettings(s: AppSettings) = viewModelScope.launch { c.settingsStore.update(s) }
    fun setupFyers(appId: String, secret: String, redirectUri: String) { c.fyersAuth.setup(appId, secret, redirectUri); messages.tryEmit("FYERS app details saved on this device (encrypted)") }
    fun logoutFyers() { c.fyersAuth.logout(); messages.tryEmit("Logged out of FYERS") }
    fun completeFyersLogin(authCode: String, pin: String, onDone: (Result<Unit>) -> Unit) = viewModelScope.launch {
        val r = c.fyersAuth.completeLogin(authCode, pin)
        if (r.isSuccess) messages.tryEmit("Logged in to FYERS. It will renew itself daily.")
        onDone(r)
    }
    fun setupUpstox(apiKey: String, secret: String, redirectUri: String) { c.upstoxAuth.setup(apiKey, secret, redirectUri); messages.tryEmit("Upstox app details saved on this device (encrypted)") }
    fun logoutUpstox() { c.upstoxAuth.logout(); messages.tryEmit("Logged out of Upstox") }
    fun completeUpstoxLogin(code: String, onDone: (Result<Unit>) -> Unit) = viewModelScope.launch {
        val r = c.upstoxAuth.completeLogin(code)
        if (r.isSuccess) messages.tryEmit("Logged in to Upstox until 3:30 AM tomorrow.")
        onDone(r)
    }
    fun reset() = viewModelScope.launch { c.trading.resetSimulation(); messages.tryEmit("Simulation reset to ₹13,54,510") }
    fun export(onText: (String) -> Unit) { viewModelScope.launch { onText(c.trading.exportJson()) } }
    fun import(text: String) { viewModelScope.launch { runCatching { c.trading.importJson(text) }.onSuccess { messages.tryEmit("Import complete") }.onFailure { messages.tryEmit("Import failed: ${it.message}") } } }

    fun addToWatchlist(listId: Long, symbol: String, name: String) = viewModelScope.launch { c.watchlists.add(listId, symbol, name) }
    fun removeFromWatchlist(listId: Long, symbol: String) = viewModelScope.launch { c.watchlists.remove(listId, symbol) }
    fun createWatchlist(name: String) = viewModelScope.launch { c.watchlists.create(name) }
    fun renameWatchlist(id: Long, name: String) = viewModelScope.launch { c.watchlists.rename(id, name) }
    fun deleteWatchlist(id: Long) = viewModelScope.launch { c.watchlists.delete(id) }
    /** Watchlist sparklines: 5-minute closes of each symbol's latest session, refetched at most every 5 minutes. */
    val sparks = MutableStateFlow<Map<String, List<BigDecimal>>>(emptyMap())
    private val sparkFetched = mutableMapOf<String, Instant>()
    private var sparkJob: Job? = null
    private var sparkProvider: String? = null
    fun loadSparks(symbols: Collection<String>) {
        if (sparkJob?.isActive == true) return
        // Demo and live prices must never share a chart.
        if (sparkProvider != provider.value.id) { sparkProvider = provider.value.id; sparkFetched.clear(); sparks.value = emptyMap() }
        sparkJob = viewModelScope.launch {
            // One at a time so a long watchlist never bursts the broker's request limit.
            symbols.filter { s -> sparkFetched[s]?.let { Instant.now().isAfter(it.plusSeconds(300)) } ?: true }.forEach { s ->
                provider.value.candles(s, CandleInterval.M5).onSuccess { candles ->
                    val day = candles.maxOfOrNull { it.time.atZone(MarketHours.IST).toLocalDate() } ?: return@onSuccess
                    sparkFetched[s] = Instant.now()
                    sparks.update { it + (s to candles.filter { c -> c.time.atZone(MarketHours.IST).toLocalDate() == day }.map { c -> c.close }) }
                }
            }
        }
    }
    suspend fun search(q: String) = provider.value.searchEquities(q)
    suspend fun equityInfo(symbol: String) = provider.value.equityInfo(symbol).onSuccess { c.contracts[symbol] = it }
}

class CandlesVM(private val c: AppContainer, private val symbol: String) : ViewModel() {
    val interval = MutableStateFlow(CandleInterval.M5)
    val state = MutableStateFlow<UiState<List<Candle>>>(UiState.Loading)
    private var job: Job? = null
    init { load() }
    fun select(i: CandleInterval) { interval.value = i; load() }
    fun load() {
        job?.cancel(); state.value = UiState.Loading
        job = viewModelScope.launch {
            state.value = c.hub.provider.value.candles(symbol, interval.value).fold({ if (it.isEmpty()) UiState.Error("The provider returned no candles for this interval") else UiState.Success(it) }, { it.toUi() })
        }
    }
}

data class ChainUi(val chain: OptionChain? = null, val error: String? = null, val loading: Boolean = true, val offline: Boolean = false)

/** Option chain of [key]: an index name ("NIFTY") or a stock symbol ("NSE:RELIANCE-EQ"). */
class ChainVM(private val c: AppContainer, private val key: String) : ViewModel() {
    private val u = Underlying.values().firstOrNull { it.name == key }
    val ui = MutableStateFlow(ChainUi())
    private var expiry: LocalDate? = null
    private var job: Job? = null
    init { start() }
    fun selectExpiry(e: LocalDate) { expiry = e; ui.update { it.copy(loading = true) }; start() }
    fun start() {
        job?.cancel()
        job = viewModelScope.launch {
            while (true) {
                c.hub.foreground.first { it } // no refreshes while the app is in the background
                val p = c.hub.provider.value
                (if (u != null) p.optionChain(u, expiry) else p.stockOptionChain(key, expiry)).fold({ ch ->
                    ch.rows.forEach { r -> listOfNotNull(r.call, r.put).forEach { s -> s.info?.let { c.contracts[s.symbol] = it }; c.hub.seed(s.quote) } }
                    expiry = ch.selectedExpiry
                    ui.value = ChainUi(ch)
                }, { e ->
                    val k = (e as? ProviderException)?.kind
                    ui.update { it.copy(error = e.message, loading = false, offline = k == ProviderException.Kind.NETWORK) }
                })
                // Market closed: the loaded chain won't change, so stop instead of re-reading it. Live data only.
                if (ui.value.chain != null && ui.value.chain?.source != com.papertrade.core.QuoteSource.DEMO && !com.papertrade.core.MarketHours.isLikelyOpen(Instant.now())) break
                delay(15_000) // 1 request / 15 s = ~480 per 2-hour session, inside FYERS's free 5,000/day
            }
        }
    }
}

class FuturesVM(private val c: AppContainer, private val u: Underlying) : ViewModel() {
    val state = MutableStateFlow<UiState<List<FutureContract>>>(UiState.Loading)
    init { load() }
    fun load() = viewModelScope.launch {
        state.value = UiState.Loading
        state.value = c.hub.provider.value.futures(u).fold({ list ->
            list.forEach { f -> c.contracts[f.info.symbol] = f.info; f.quote?.let(c.hub::seed) }
            UiState.Success(list)
        }, { it.toUi() })
    }
}

class Factory(private val c: AppContainer) : ViewModelProvider.Factory {
    var arg: String = ""
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        AppVM::class.java -> AppVM(c)
        CandlesVM::class.java -> CandlesVM(c, arg)
        ChainVM::class.java -> ChainVM(c, arg)
        FuturesVM::class.java -> FuturesVM(c, Underlying.valueOf(arg))
        else -> error("Unknown VM $modelClass")
    } as T
}
