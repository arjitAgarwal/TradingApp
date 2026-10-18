package com.papertrade.core

import java.math.BigDecimal
import java.math.RoundingMode

private val HUNDRED = BigDecimal(100)

object ChargeCalculator {
    /** Itemised charges for one fill. Turnover = price x qty x multiplier. Rounded to 2 dp. */
    fun charges(model: ChargeModel, inst: Instrument, side: Side, qty: Int, price: BigDecimal): BigDecimal {
        val r = model.forSegment(inst.segment)
        val turnover = price * BigDecimal(qty) * BigDecimal(inst.multiplier)
        val pct = { v: BigDecimal -> Dec.div(turnover * v, HUNDRED) }
        val brokeragePct = pct(r.brokeragePctOfTurnover)
        val brokerage = if (r.brokerageFlat.signum() == 0 && r.brokeragePctOfTurnover.signum() == 0) BigDecimal.ZERO
        else if (r.brokerageFlat.signum() == 0) brokeragePct else brokeragePct.min(r.brokerageFlat)
        val stt = pct(if (side == Side.BUY) r.sttPctBuy else r.sttPctSell)
        val exch = pct(r.exchangePct)
        val sebi = pct(r.sebiPct)
        val stamp = if (side == Side.BUY) pct(r.stampPctBuy) else BigDecimal.ZERO
        val gst = Dec.div((brokerage + exch + sebi) * r.gstPct, HUNDRED)
        return Dec.money(brokerage + stt + exch + sebi + stamp + gst)
    }
}

data class PositionUpdate(val position: Position, val realizedGrossDelta: BigDecimal)

object PositionMath {
    /**
     * Average-cost method. Increasing a position re-averages; reducing realises P&L against the average;
     * crossing zero (reversal) closes the old side at the average and opens the remainder at the fill price.
     * realised = (fillPrice - avg) x closedQty x multiplier x sign(oldQty)
     */
    fun apply(pos: Position, side: Side, qty: Int, price: BigDecimal, multiplier: Int, charges: BigDecimal): PositionUpdate {
        require(qty > 0)
        val signedFill = side.sign * qty
        val old = pos.quantity
        var realized = BigDecimal.ZERO
        val newQty: Int
        var newAvg = pos.avgPrice
        if (old == 0 || (old > 0) == (signedFill > 0)) {
            newQty = old + signedFill
            val totalAbs = BigDecimal(kotlin.math.abs(old) + qty)
            newAvg = (pos.avgPrice * BigDecimal(kotlin.math.abs(old)) + price * BigDecimal(qty))
                .divide(totalAbs, Dec.PRICE_SCALE, RoundingMode.HALF_EVEN)
        } else {
            val closing = minOf(kotlin.math.abs(old), qty)
            val dir = if (old > 0) 1 else -1
            realized = (price - pos.avgPrice) * BigDecimal(closing) * BigDecimal(multiplier) * BigDecimal(dir)
            newQty = old + signedFill
            if (newQty != 0 && (newQty > 0) != (old > 0)) newAvg = Dec.price(price) // reversal
            if (newQty == 0) newAvg = Dec.ZERO
        }
        return PositionUpdate(
            pos.copy(quantity = newQty, avgPrice = newAvg, realizedGross = pos.realizedGross + realized, charges = pos.charges + charges),
            realized,
        )
    }

    fun unrealized(pos: Position, inst: Instrument, ltp: BigDecimal): BigDecimal =
        (ltp - pos.avgPrice) * BigDecimal(pos.quantity) * BigDecimal(inst.multiplier)
}

data class PositionValuation(
    val instrumentId: String,
    val unrealized: BigDecimal,
    val marketValue: BigDecimal,
    val realizedGross: BigDecimal,
    val charges: BigDecimal,
    val netPnl: BigDecimal,
)

data class PortfolioValuation(
    val cash: BigDecimal,
    val reservedMargin: BigDecimal,
    val availableCash: BigDecimal,
    val positionsValue: BigDecimal,
    val equity: BigDecimal,
    val realizedGross: BigDecimal,
    val unrealized: BigDecimal,
    val totalCharges: BigDecimal,
    val netPnl: BigDecimal,
    val positions: List<PositionValuation>,
)

object Portfolio {
    /**
     * Cash-flow model (no double counting):
     *  - EQUITY / OPTION: cash moves by the full trade value; contribution to equity = signedQty x multiplier x LTP.
     *  - FUTURES: no cash moves on entry; only realised P&L settles to cash; contribution = unrealised P&L.
     * equity = cash + sum(contributions);  netPnl = equity - startingCapital = realised + unrealised - charges.
     * Positions with no usable quote are valued at their average price (unrealised = 0) and flagged by the caller.
     */
    fun value(state: AccountState, instruments: Map<String, Instrument>, ltp: (String) -> BigDecimal?): PortfolioValuation {
        val rows = state.positions.values.filter { it.isOpen || it.realizedGross.signum() != 0 || it.charges.signum() != 0 }.mapNotNull { p ->
            val inst = instruments[p.instrumentId] ?: return@mapNotNull null // metadata not loaded yet
            val px = if (p.isOpen) (ltp(p.instrumentId) ?: p.avgPrice) else p.avgPrice
            val unreal = if (p.isOpen) PositionMath.unrealized(p, inst, px) else BigDecimal.ZERO
            val mv = when {
                !p.isOpen -> BigDecimal.ZERO
                inst.segment == Segment.FUTURES -> unreal
                else -> px * BigDecimal(p.quantity) * BigDecimal(inst.multiplier)
            }
            PositionValuation(p.instrumentId, Dec.money(unreal), Dec.money(mv), Dec.money(p.realizedGross), Dec.money(p.charges),
                Dec.money(p.realizedGross + unreal - p.charges))
        }
        val posValue = rows.fold(BigDecimal.ZERO) { a, r -> a + r.marketValue }
        val realized = rows.fold(BigDecimal.ZERO) { a, r -> a + r.realizedGross }
        val unreal = rows.fold(BigDecimal.ZERO) { a, r -> a + r.unrealized }
        val charges = rows.fold(BigDecimal.ZERO) { a, r -> a + r.charges }
        val equity = Dec.money(state.wallet.cash + posValue)
        return PortfolioValuation(
            Dec.money(state.wallet.cash), Dec.money(state.wallet.reservedMargin), Dec.money(state.wallet.available),
            Dec.money(posValue), equity, realized, unreal, charges, Dec.money(equity - state.wallet.startingCapital), rows,
        )
    }
}
