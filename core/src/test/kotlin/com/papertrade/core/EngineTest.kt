package com.papertrade.core

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

private fun d(s: String) = BigDecimal(s)
private val T0 = Instant.parse("2026-10-16T04:00:00Z")
private val TODAY = LocalDate.of(2026, 10, 16)

private val RELIANCE = Instrument("NSE:RELIANCE-EQ", "RELIANCE", "Reliance", Segment.EQUITY, 1, d("0.05"))
private val FUT = Instrument("FUT1", "NIFTYFUT", "Nifty Fut", Segment.FUTURES, 75, d("0.05"), expiry = LocalDate.of(2026, 10, 27))
private val CALL = Instrument("CE1", "NIFTY 25000 CE", "Nifty call", Segment.OPTION, 75, d("0.05"), LocalDate.of(2026, 10, 20), d("25000"), OptionType.CE)
private val OLD = Instrument("OLD", "OLD", "Expired", Segment.FUTURES, 75, d("0.05"), expiry = LocalDate.of(2026, 10, 1))
private val INSTS = listOf(RELIANCE, FUT, CALL, OLD).associateBy { it.id }

private class Fx(cfg: ExecutionConfig = ExecutionConfig(charges = ChargeModel.ZERO)) {
    var n = 0
    val ex = PaperExchange(cfg, { INSTS[it] }, { "O${++n}" }, { TODAY })
    var s = AccountState.initial(d("1000000"), T0)
    var now = T0
    fun q(id: String, ltp: String, bid: String? = null, ask: String? = null, at: Instant = now, src: QuoteSource = QuoteSource.TEST) =
        Quote(id, d(ltp), bid?.let(::d), ask?.let(::d), at, src)
    fun place(id: String, side: Side, qty: Int, quote: Quote?, type: OrderType = OrderType.MARKET, limit: String? = null): EngineResult {
        val r = ex.place(s, OrderRequest(id, side, type, qty, limit?.let(::d)), quote, now); s = r.state; return r
    }
    fun tick(quote: Quote): EngineResult { val r = ex.onQuote(s, quote, now); s = r.state; return r }
    fun value(prices: Map<String, String>) = Portfolio.value(s, INSTS) { prices[it]?.let(::d) }
}

class EngineTest {
    @Test fun initialWallet() {
        val f = Fx()
        assertEquals(d("1000000"), f.s.wallet.cash); assertEquals(d("1000000"), f.s.wallet.startingCapital)
        assertEquals(1, f.s.ledger.size)
    }

    @Test fun marketBuyUsesAskAndSellUsesBid() {
        val f = Fx()
        val b = f.place(RELIANCE.id, Side.BUY, 10, f.q(RELIANCE.id, "100", "99.95", "100.05"))
        assertEquals(OrderStatus.FILLED, b.order!!.status); assertEquals(d("100.05"), b.newFills[0].price)
        val s = f.place(RELIANCE.id, Side.SELL, 10, f.q(RELIANCE.id, "100", "99.95", "100.05"))
        assertEquals(d("99.95"), s.newFills[0].price)
        assertEquals(d("-1.00"), f.value(emptyMap()).netPnl) // 10 x 0.10 spread
    }

    @Test fun ltpOnlyUsesSlippageAndTickRounding() {
        val f = Fx(ExecutionConfig(ltpSlippageBps = d("10"), charges = ChargeModel.ZERO))
        val r = f.place(RELIANCE.id, Side.BUY, 1, f.q(RELIANCE.id, "100.00"))
        assertEquals(0, d("100.10").compareTo(r.newFills[0].price))
        val s = f.place(RELIANCE.id, Side.SELL, 1, f.q(RELIANCE.id, "100.00"))
        assertEquals(0, d("99.90").compareTo(s.newFills[0].price))
    }

    @Test fun staleMissingAndCachedQuotesRejectMarketOrders() {
        val f = Fx()
        assertEquals(OrderStatus.REJECTED, f.place(RELIANCE.id, Side.BUY, 1, null).order!!.status)
        val old = f.q(RELIANCE.id, "100", at = T0.minus(Duration.ofMinutes(5)))
        assertEquals(OrderStatus.REJECTED, f.place(RELIANCE.id, Side.BUY, 1, old).order!!.status)
        val cached = f.q(RELIANCE.id, "100", src = QuoteSource.CACHED)
        assertTrue(f.place(RELIANCE.id, Side.BUY, 1, cached).error!!.contains("cached"))
        assertEquals(d("1000000"), f.s.wallet.cash)
        assertTrue(f.s.fills.isEmpty())
    }

    @Test fun limitBuyWaitsThenFillsOnlyOnNewEligibleQuote() {
        val f = Fx()
        val r = f.place(RELIANCE.id, Side.BUY, 10, f.q(RELIANCE.id, "100", "99.95", "100.05"), OrderType.LIMIT, "99.50")
        assertEquals(OrderStatus.OPEN, r.order!!.status)
        // Same quote again (screen refresh) must not do anything even if re-sent
        val same = f.q(RELIANCE.id, "100", "99.95", "100.05")
        assertTrue(f.tick(same).newFills.isEmpty())
        f.now = f.now.plusSeconds(2)
        assertTrue(f.tick(f.q(RELIANCE.id, "99.7", "99.65", "99.75")).newFills.isEmpty())
        f.now = f.now.plusSeconds(2)
        val hit = f.tick(f.q(RELIANCE.id, "99.4", "99.35", "99.45"))
        assertEquals(1, hit.newFills.size); assertEquals(d("99.45"), hit.newFills[0].price) // never worse than limit
        assertEquals(OrderStatus.FILLED, f.s.orders["O1"]!!.status)
    }

    @Test fun oldTickNeverFillsAndSameTimestampIsDuplicate() {
        val f = Fx()
        f.place(RELIANCE.id, Side.BUY, 1, f.q(RELIANCE.id, "100", "99.95", "100.05"), OrderType.LIMIT, "99.00")
        f.now = f.now.plusSeconds(3)
        val q = f.q(RELIANCE.id, "98.9", "98.85", "98.95", at = f.now)
        assertEquals(1, f.tick(q).newFills.size)
        // replay of the identical event must not create a second fill
        assertEquals(0, f.tick(q).newFills.size); assertEquals(1, f.s.fills.size)
    }

    @Test fun limitSellMarketableAtPlacementFillsAtBidAndGapIsHonoured() {
        val f = Fx()
        f.place(RELIANCE.id, Side.BUY, 10, f.q(RELIANCE.id, "100", "100", "100"))
        val r = f.place(RELIANCE.id, Side.SELL, 10, f.q(RELIANCE.id, "103", "102.95", "103.05"), OrderType.LIMIT, "102.00")
        assertEquals(d("102.95"), r.newFills[0].price) // better than limit
    }

    @Test fun staleQuoteDoesNotFillLimit() {
        val f = Fx()
        f.place(RELIANCE.id, Side.BUY, 1, null, OrderType.LIMIT, "100.00")
        f.now = f.now.plusSeconds(120)
        val stale = f.q(RELIANCE.id, "90", "90", "90", at = T0.plusSeconds(1))
        assertTrue(f.tick(stale).newFills.isEmpty())
    }

    @Test fun partialFillsProgressToFilled() {
        val f = Fx(ExecutionConfig(maxQtyPerFill = 4, charges = ChargeModel.ZERO))
        val r = f.place(RELIANCE.id, Side.BUY, 10, f.q(RELIANCE.id, "100", "100", "100"))
        assertEquals(OrderStatus.PARTIALLY_FILLED, r.order!!.status); assertEquals(4, r.order!!.filledQuantity)
        f.now = f.now.plusSeconds(1); f.tick(f.q(RELIANCE.id, "102", "102", "102"))
        assertEquals(OrderStatus.PARTIALLY_FILLED, f.s.orders["O1"]!!.status)
        f.now = f.now.plusSeconds(1); f.tick(f.q(RELIANCE.id, "104", "104", "104"))
        val o = f.s.orders["O1"]!!
        assertEquals(OrderStatus.FILLED, o.status); assertEquals(10, o.filledQuantity)
        assertEquals(0, d("101.6").compareTo(o.avgFillPrice!!)) // (4*100+4*102+2*104)/10
    }

    @Test fun cancelRules() {
        val f = Fx()
        f.place(RELIANCE.id, Side.BUY, 1, null, OrderType.LIMIT, "90.00")
        assertEquals(OrderStatus.CANCELLED, f.ex.cancel(f.s, "O1", f.now).also { f.s = it.state }.order!!.status)
        assertNotNull(f.ex.cancel(f.s, "O1", f.now).error) // already cancelled
        f.place(RELIANCE.id, Side.BUY, 1, f.q(RELIANCE.id, "100", "100", "100"))
        assertNotNull(f.ex.cancel(f.s, "O2", f.now).error) // filled
    }

    @Test fun stateMachineRejectsInvalidTransitions() {
        val o = Order("x", "i", Side.BUY, OrderType.MARKET, 1, null, createdAt = T0, updatedAt = T0, status = OrderStatus.FILLED)
        try { OrderStateMachine.transition(o, OrderStatus.OPEN, T0); fail() } catch (_: InvalidTransitionException) {}
        assertFalse(OrderStateMachine.canTransition(OrderStatus.CREATED, OrderStatus.FILLED))
        assertTrue(OrderStateMachine.canTransition(OrderStatus.OPEN, OrderStatus.PARTIALLY_FILLED))
    }

    @Test fun duplicateFillApplicationIsIdempotent() {
        val f = Fx()
        val r = f.place(RELIANCE.id, Side.BUY, 5, f.q(RELIANCE.id, "100", "100", "100"))
        val stale = r.order!!.copy(fillSeq = 0, filledQuantity = 0, status = OrderStatus.OPEN) // pretend a retry of fill #1
        val again = f.ex.applyFill(f.s, stale, RELIANCE, 5, d("100"), QuoteSource.TEST, f.now)
        assertEquals(f.s, again.state)
    }

    @Test fun equityHoldingValuationAndRealised() {
        val f = Fx()
        f.place(RELIANCE.id, Side.BUY, 10, f.q(RELIANCE.id, "100", "100", "100"))
        var v = f.value(mapOf(RELIANCE.id to "110"))
        assertEquals(d("100.00"), v.unrealized); assertEquals(d("100.00"), v.netPnl)
        assertEquals(d("999000.00"), v.cash); assertEquals(d("1100.00"), v.positionsValue); assertEquals(d("1000100.00"), v.equity)
        f.place(RELIANCE.id, Side.SELL, 4, f.q(RELIANCE.id, "110", "110", "110"))
        v = f.value(mapOf(RELIANCE.id to "110"))
        assertEquals(d("40.00"), v.realizedGross); assertEquals(d("60.00"), v.unrealized); assertEquals(d("100.00"), v.netPnl)
    }

    @Test fun equityCannotBeSoldShortOrOverheld() {
        val f = Fx()
        assertTrue(f.place(RELIANCE.id, Side.SELL, 1, f.q(RELIANCE.id, "100", "100", "100")).error!!.contains("held"))
        f.place(RELIANCE.id, Side.BUY, 2, f.q(RELIANCE.id, "100", "100", "100"))
        assertNotNull(f.place(RELIANCE.id, Side.SELL, 3, f.q(RELIANCE.id, "100", "100", "100")).error)
    }

    @Test fun futuresMultiplierMarginAndPnl() {
        val f = Fx()
        f.place(FUT.id, Side.BUY, 2, f.q(FUT.id, "25000", "25000", "25000"))
        // reserved = 2 lots x 75 x 25000 x 12% = 450,000
        assertEquals(d("450000.00"), f.s.wallet.reservedMargin)
        assertEquals(d("1000000"), f.s.wallet.cash) // nothing moves on entry
        val v = f.value(mapOf(FUT.id to "25010"))
        assertEquals(d("1500.00"), v.unrealized) // 10 x 2 x 75
        f.place(FUT.id, Side.SELL, 2, f.q(FUT.id, "25010", "25010", "25010"))
        assertEquals(d("1001500.00"), f.s.wallet.cash); assertEquals(d("0.00"), f.s.wallet.reservedMargin)
    }

    @Test fun shortFuturesProfitsWhenPriceFalls() {
        val f = Fx()
        f.place(FUT.id, Side.SELL, 1, f.q(FUT.id, "25000", "25000", "25000"))
        assertEquals(d("750.00"), f.value(mapOf(FUT.id to "24990")).unrealized)
        assertEquals(d("-750.00"), f.value(mapOf(FUT.id to "25010")).unrealized)
    }

    @Test fun positionAveragingReductionAndReversal() {
        var p = Position("x")
        p = PositionMath.apply(p, Side.BUY, 10, d("100"), 1, d("0")).position
        p = PositionMath.apply(p, Side.BUY, 10, d("110"), 1, d("0")).position
        assertEquals(20, p.quantity); assertEquals(0, d("105").compareTo(p.avgPrice))
        val red = PositionMath.apply(p, Side.SELL, 5, d("120"), 1, d("0"))
        assertEquals(0, d("75").compareTo(red.realizedGrossDelta)); assertEquals(15, red.position.quantity)
        assertEquals(0, d("105").compareTo(red.position.avgPrice))
        val rev = PositionMath.apply(red.position, Side.SELL, 25, d("100"), 1, d("0")) // closes 15 @ -5, opens short 10 @100
        assertEquals(0, d("-75").compareTo(rev.realizedGrossDelta))
        assertEquals(-10, rev.position.quantity); assertEquals(0, d("100").compareTo(rev.position.avgPrice))
        val cover = PositionMath.apply(rev.position, Side.BUY, 10, d("90"), 1, d("0"))
        assertEquals(0, d("100").compareTo(cover.realizedGrossDelta)); assertEquals(0, cover.position.quantity)
    }

    @Test fun reversalWithMultiplierOnFutures() {
        val f = Fx()
        f.place(FUT.id, Side.BUY, 1, f.q(FUT.id, "25000", "25000", "25000"))
        f.now = f.now.plusSeconds(1)
        f.place(FUT.id, Side.SELL, 3, f.q(FUT.id, "25020", "25020", "25020")) // close 1 (+1500) and go short 2
        assertEquals(-2, f.s.positions[FUT.id]!!.quantity)
        assertEquals(d("1001500.00"), f.s.wallet.cash)
        assertEquals(d("1500.00"), f.value(mapOf(FUT.id to "25020")).realizedGross)
    }

    @Test fun optionLongAndShortWithLotSizeAndMargin() {
        val f = Fx()
        f.place(CALL.id, Side.BUY, 2, f.q(CALL.id, "100", "100", "100")) // 2 lots x 75 x 100 = 15000
        assertEquals(d("985000.00"), f.s.wallet.cash)
        f.now = f.now.plusSeconds(1)
        f.place(CALL.id, Side.SELL, 2, f.q(CALL.id, "120", "120", "120"))
        assertEquals(d("1000000.00").add(d("3000.00")), f.s.wallet.cash)
        // naked short: premium credited, margin on strike notional (NOT premium-capped)
        f.now = f.now.plusSeconds(1)
        f.place(CALL.id, Side.SELL, 1, f.q(CALL.id, "50", "50", "50"))
        assertEquals(d("281250.00"), f.s.wallet.reservedMargin) // 1 x 75 x 25000 x 15%
        val v = f.value(mapOf(CALL.id to "300")) // option rallies: loss far exceeds premium collected
        assertEquals(d("-18750.00"), v.unrealized)
    }

    @Test fun marginRejectsOversizedFuturesOrder() {
        val f = Fx()
        val r = f.place(FUT.id, Side.BUY, 20, f.q(FUT.id, "25000", "25000", "25000")) // needs 4.5M
        assertEquals(OrderStatus.REJECTED, r.order!!.status); assertTrue(r.error!!.contains("Insufficient"))
        assertTrue(f.s.positions.isEmpty()); assertEquals(d("1000000"), f.s.wallet.cash)
    }

    @Test fun expiredContractAndBadTickRejected() {
        val f = Fx()
        assertTrue(f.place(OLD.id, Side.BUY, 1, f.q(OLD.id, "100", "100", "100")).error!!.contains("expired"))
        assertTrue(f.place(RELIANCE.id, Side.BUY, 1, null, OrderType.LIMIT, "100.03").error!!.contains("tick"))
        assertNotNull(f.place("nope", Side.BUY, 1, null).error)
    }

    @Test fun ledgerAlwaysSumsToCashAndPnlIdentityHolds() {
        val f = Fx(ExecutionConfig(charges = ChargeModel.ILLUSTRATIVE))
        f.place(RELIANCE.id, Side.BUY, 100, f.q(RELIANCE.id, "2500", "2500", "2500")); f.now = f.now.plusSeconds(1)
        f.place(FUT.id, Side.BUY, 1, f.q(FUT.id, "25000", "25000", "25000")); f.now = f.now.plusSeconds(1)
        f.place(FUT.id, Side.SELL, 1, f.q(FUT.id, "25040", "25040", "25040")); f.now = f.now.plusSeconds(1)
        f.place(CALL.id, Side.BUY, 1, f.q(CALL.id, "80", "80", "80")); f.now = f.now.plusSeconds(1)
        f.place(RELIANCE.id, Side.SELL, 40, f.q(RELIANCE.id, "2510", "2510", "2510"))
        assertEquals(f.s.wallet.cash, f.s.ledger.fold(BigDecimal.ZERO) { a, e -> a + e.amount })
        val v = f.value(mapOf(RELIANCE.id to "2520", CALL.id to "90", FUT.id to "25040"))
        assertEquals(v.netPnl, Dec.money(v.realizedGross + v.unrealized - v.totalCharges))
        assertTrue(v.totalCharges.signum() > 0)
    }

    @Test fun chargesAreItemisedAndDeterministic() {
        val c = ChargeCalculator.charges(ChargeModel.ILLUSTRATIVE, FUT, Side.SELL, 1, d("25000"))
        // turnover 1,875,000: brokerage min(0.03%=562.5, 20)=20; STT 0.02%=375; exch .00173%=32.4375; sebi .0001%=1.875; gst 18%*(20+32.4375+1.875)=9.776
        assertEquals(d("439.09"), c)
        assertEquals(c, ChargeCalculator.charges(ChargeModel.ILLUSTRATIVE, FUT, Side.SELL, 1, d("25000")))
        assertEquals(d("0.00"), ChargeCalculator.charges(ChargeModel.ZERO, FUT, Side.SELL, 1, d("25000")))
    }

    @Test fun missingQuoteValuesAtCostNotInvented() {
        val f = Fx()
        f.place(RELIANCE.id, Side.BUY, 10, f.q(RELIANCE.id, "100", "100", "100"))
        val v = f.value(emptyMap())
        assertEquals(d("0.00"), v.unrealized); assertEquals(d("0.00"), v.netPnl)
    }
}
