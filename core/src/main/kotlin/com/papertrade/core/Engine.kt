package com.papertrade.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

class InvalidTransitionException(from: OrderStatus, to: OrderStatus) : IllegalStateException("Invalid order transition $from -> $to")

object OrderStateMachine {
    private val allowed: Map<OrderStatus, Set<OrderStatus>> = mapOf(
        OrderStatus.CREATED to setOf(OrderStatus.OPEN, OrderStatus.REJECTED, OrderStatus.CANCELLED),
        OrderStatus.OPEN to setOf(OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED, OrderStatus.CANCELLED, OrderStatus.REJECTED),
        OrderStatus.PARTIALLY_FILLED to setOf(OrderStatus.PARTIALLY_FILLED, OrderStatus.FILLED, OrderStatus.CANCELLED),
        OrderStatus.FILLED to emptySet(), OrderStatus.CANCELLED to emptySet(), OrderStatus.REJECTED to emptySet(),
    )
    fun canTransition(from: OrderStatus, to: OrderStatus) = to in allowed.getValue(from)
    fun transition(o: Order, to: OrderStatus, now: Instant): Order {
        if (!canTransition(o.status, to)) throw InvalidTransitionException(o.status, to)
        return o.copy(status = to, updatedAt = now)
    }
}

data class EngineResult(
    val state: AccountState,
    val order: Order? = null,
    val newFills: List<Fill> = emptyList(),
    val error: String? = null,
)

/**
 * Deterministic paper-trading engine. Pure: takes state in, returns new state. No I/O, no clock, no randomness
 * (ids and time are injected), so every rule is unit-testable. NEVER talks to a broker.
 *
 * EXECUTION RULES (documented in README):
 *  - Fill reference: BUY uses ASK, SELL uses BID. If bid/ask are missing, LTP is used (labelled LTP model) with optional slippage in bps. LTP is not guaranteed executable.
 *  - MARKET orders are rejected if there is no quote, the quote is older than maxQuoteAge, or its source is CACHED.
 *  - LIMIT BUY fills when reference <= limit, SELL when reference >= limit, at the reference (price improvement/gap fills at market, never worse than limit).
 *  - A quote is evaluated at most once per order (by timestamp); an unchanged or older quote never fills anything, so a screen refresh cannot fill an order.
 *  - Stale/cached quotes never fill. Orders stay OPEN across market close; the caller decides whether to expire them.
 *  - Partial fills: if maxQtyPerFill is set each evaluation fills at most that quantity.
 */
class PaperExchange(
    private val config: ExecutionConfig,
    private val instruments: (String) -> Instrument?,
    private val newOrderId: () -> String,
    private val today: () -> LocalDate,
) {
    fun place(state: AccountState, req: OrderRequest, quote: Quote?, now: Instant): EngineResult {
        val inst = instruments(req.instrumentId)
        val base = Order(newOrderId(), req.instrumentId, req.side, req.type, req.quantity, req.limitPrice, createdAt = now, updatedAt = now)
        fun reject(reason: String): EngineResult {
            val o = OrderStateMachine.transition(base, OrderStatus.REJECTED, now).copy(rejectReason = reason)
            return EngineResult(state.copy(orders = state.orders + (o.id to o)), o, error = reason)
        }
        if (inst == null) return reject("Unknown instrument")
        if (req.quantity <= 0) return reject("Quantity must be positive")
        if (req.type == OrderType.LIMIT) {
            val lp = req.limitPrice ?: return reject("Limit price required")
            if (lp.signum() <= 0) return reject("Limit price must be positive")
            if (lp.remainder(inst.tickSize).signum() != 0) return reject("Limit price must be a multiple of tick size ${inst.tickSize.toPlainString()}")
        } else if (req.limitPrice != null) return reject("Market order cannot carry a limit price")
        if (inst.expiry != null && inst.expiry < today()) return reject("Contract expired on ${inst.expiry}")
        val held = state.positions[inst.id]?.quantity ?: 0
        if (inst.segment == Segment.EQUITY && req.side == Side.SELL && req.quantity > held)
            return reject("Cannot sell ${req.quantity}; only $held held (short selling of delivery equity is not simulated)")
        if (req.type == OrderType.MARKET) {
            if (quote == null) return reject("No price available")
            val why = staleReason(quote, now)
            if (why != null) return reject("Price unusable: $why")
        }
        val open = OrderStateMachine.transition(base, OrderStatus.OPEN, now)
        val withOrder = state.copy(orders = state.orders + (open.id to open))
        return if (quote != null) evaluate(withOrder, open.id, inst, quote, now) else EngineResult(withOrder, open)
    }

    /** Feed a new quote; fills any eligible active orders on that instrument. */
    fun onQuote(state: AccountState, quote: Quote, now: Instant): EngineResult {
        val inst = instruments(quote.instrumentId) ?: return EngineResult(state)
        var s = state
        val fills = mutableListOf<Fill>()
        state.orders.values.filter { it.instrumentId == quote.instrumentId && it.status.isActive }.sortedBy { it.createdAt }.forEach { o ->
            val r = evaluate(s, o.id, inst, quote, now)
            s = r.state; fills += r.newFills
        }
        return EngineResult(s, newFills = fills)
    }

    fun cancel(state: AccountState, orderId: String, now: Instant): EngineResult {
        val o = state.orders[orderId] ?: return EngineResult(state, error = "Order not found")
        if (o.status.isTerminal) return EngineResult(state, o, error = "Order already ${o.status}")
        val c = OrderStateMachine.transition(o, OrderStatus.CANCELLED, now)
        return EngineResult(state.copy(orders = state.orders + (c.id to c)), c)
    }

    fun staleReason(q: Quote, now: Instant): String? = when {
        q.source == QuoteSource.CACHED -> "quote is cached"
        q.ltp.signum() <= 0 -> "invalid price"
        java.time.Duration.between(q.timestamp, now) > config.maxQuoteAge -> "quote is stale (older than ${config.maxQuoteAge.seconds}s)"
        else -> null
    }

    fun referencePrice(side: Side, q: Quote, tick: BigDecimal): BigDecimal {
        val explicit = if (side == Side.BUY) q.ask else q.bid
        if (explicit != null && explicit.signum() > 0) return explicit
        val bps = config.ltpSlippageBps
        val adj = if (side == Side.BUY) BigDecimal.ONE + Dec.div(bps, BigDecimal(10000)) else BigDecimal.ONE - Dec.div(bps, BigDecimal(10000))
        val raw = q.ltp * adj
        val mode = if (side == Side.BUY) RoundingMode.CEILING else RoundingMode.FLOOR
        return raw.divide(tick, 0, mode) * tick
    }

    private fun evaluate(state: AccountState, orderId: String, inst: Instrument, q: Quote, now: Instant): EngineResult {
        val o = state.orders.getValue(orderId)
        if (!o.status.isActive) return EngineResult(state, o)
        if (o.lastQuoteTs != null && !q.timestamp.isAfter(o.lastQuoteTs)) return EngineResult(state, o) // duplicate/old tick
        if (staleReason(q, now) != null) return EngineResult(state, o)
        val ref = referencePrice(o.side, q, inst.tickSize)
        val eligible = when (o.type) {
            OrderType.MARKET -> true
            OrderType.LIMIT -> if (o.side == Side.BUY) ref <= o.limitPrice!! else ref >= o.limitPrice!!
        }
        val seen = o.copy(lastQuoteTs = q.timestamp)
        if (!eligible) return EngineResult(state.copy(orders = state.orders + (o.id to seen)), seen)
        val qty = minOf(o.remaining, config.maxQtyPerFill ?: o.remaining)
        return applyFill(state.copy(orders = state.orders + (o.id to seen)), seen, inst, qty, ref, q.source, now)
    }

    /** Applies exactly one fill. Idempotent on fill id. Wallet, ledger, position and order change together. */
    fun applyFill(state: AccountState, order: Order, inst: Instrument, qty: Int, price: BigDecimal, src: QuoteSource, now: Instant): EngineResult {
        val fillId = "${order.id}-F${order.fillSeq + 1}"
        if (state.fills.any { it.id == fillId }) return EngineResult(state, order) // duplicate protection
        val charges = ChargeCalculator.charges(config.charges, inst, order.side, qty, price)
        val pos0 = state.positions[inst.id] ?: Position(inst.id)
        val upd = PositionMath.apply(pos0, order.side, qty, price, inst.multiplier, charges)
        val value = Dec.money(price * BigDecimal(qty) * BigDecimal(inst.multiplier))
        val ledger = mutableListOf<LedgerEntry>()
        var cashDelta = BigDecimal.ZERO
        if (inst.segment == Segment.FUTURES) {
            val settle = Dec.money(upd.realizedGrossDelta)
            if (settle.signum() != 0) { ledger += LedgerEntry("$fillId:settle", LedgerType.FUTURES_SETTLEMENT, settle, fillId, now, "Futures P&L settled"); cashDelta += settle }
        } else {
            val tv = if (order.side == Side.BUY) value.negate() else value
            ledger += LedgerEntry("$fillId:value", LedgerType.TRADE_VALUE, tv, fillId, now, "${order.side} ${inst.symbol}"); cashDelta += tv
        }
        if (charges.signum() != 0) { ledger += LedgerEntry("$fillId:charges", LedgerType.CHARGES, charges.negate(), fillId, now, "Simulated charges"); cashDelta -= charges }

        val newPositions = state.positions + (inst.id to upd.position)
        val newCash = state.wallet.cash + cashDelta
        val newReserved = Dec.money(reserved(newPositions))
        val reducesRisk = (pos0.quantity != 0) && (pos0.quantity > 0) != (order.side == Side.BUY) && qty <= kotlin.math.abs(pos0.quantity)
        if (!reducesRisk && newCash - newReserved < BigDecimal.ZERO) {
            val reason = "Insufficient funds: need margin/cash, available would be ${Dec.money(newCash - newReserved)}"
            val failed = if (order.filledQuantity == 0) OrderStateMachine.transition(order, OrderStatus.REJECTED, now) else OrderStateMachine.transition(order, OrderStatus.CANCELLED, now)
            val f2 = failed.copy(rejectReason = reason)
            return EngineResult(state.copy(orders = state.orders + (f2.id to f2)), f2, error = reason)
        }
        val fill = Fill(fillId, order.id, inst.id, order.side, qty, price, charges, upd.realizedGrossDelta, now, src)
        val filledQty = order.filledQuantity + qty
        val avg = if (order.avgFillPrice == null) price
        else ((order.avgFillPrice * BigDecimal(order.filledQuantity) + price * BigDecimal(qty)).divide(BigDecimal(filledQty), Dec.PRICE_SCALE, RoundingMode.HALF_EVEN))
        val status = if (filledQty == order.quantity) OrderStatus.FILLED else OrderStatus.PARTIALLY_FILLED
        val newOrder = OrderStateMachine.transition(order, status, now).copy(filledQuantity = filledQty, avgFillPrice = avg, executedAt = now, fillSeq = order.fillSeq + 1)
        val newState = state.copy(
            wallet = state.wallet.copy(cash = newCash, reservedMargin = newReserved),
            orders = state.orders + (newOrder.id to newOrder),
            positions = newPositions,
            fills = state.fills + fill,
            ledger = state.ledger + ledger,
        )
        return EngineResult(newState, newOrder, listOf(fill))
    }

    /** Simulated margin earmark, recomputed from open positions so it can never drift. */
    fun reserved(positions: Map<String, Position>): BigDecimal = positions.values.filter { it.isOpen }.fold(BigDecimal.ZERO) { acc, p ->
        val inst = instruments(p.instrumentId) ?: return@fold acc
        val q = BigDecimal(kotlin.math.abs(p.quantity)) * BigDecimal(inst.multiplier)
        acc + when {
            inst.segment == Segment.FUTURES -> Dec.div(q * p.avgPrice * config.margin.futuresPct, BigDecimal(100))
            inst.segment == Segment.OPTION && p.quantity < 0 -> Dec.div(q * inst.strike!! * config.margin.shortOptionPct, BigDecimal(100))
            else -> BigDecimal.ZERO
        }
    }
}
