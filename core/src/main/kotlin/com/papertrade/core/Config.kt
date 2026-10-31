package com.papertrade.core

import java.math.BigDecimal
import java.time.Duration

/** Per-segment cost assumptions. DEFAULTS ARE ILLUSTRATIVE, NOT OFFICIAL: verify current exchange/SEBI/government rates. All pct values are percent (0.1 = 0.1%). */
data class ChargeRates(
    val brokerageFlat: BigDecimal,
    val brokeragePctOfTurnover: BigDecimal,
    val sttPctBuy: BigDecimal,
    val sttPctSell: BigDecimal,
    val exchangePct: BigDecimal,
    val sebiPct: BigDecimal,
    val stampPctBuy: BigDecimal,
    val gstPct: BigDecimal,
)

data class ChargeModel(val equity: ChargeRates, val futures: ChargeRates, val options: ChargeRates) {
    fun forSegment(s: Segment) = when (s) { Segment.EQUITY -> equity; Segment.FUTURES -> futures; Segment.OPTION -> options }

    companion object {
        private fun d(s: String) = BigDecimal(s)
        val ILLUSTRATIVE = ChargeModel(
            equity = ChargeRates(d("0"), d("0"), d("0.1"), d("0.1"), d("0.00297"), d("0.0001"), d("0.015"), d("18")),
            futures = ChargeRates(d("20"), d("0.03"), d("0"), d("0.02"), d("0.00173"), d("0.0001"), d("0.002"), d("18")),
            options = ChargeRates(d("20"), d("0.03"), d("0"), d("0.1"), d("0.03503"), d("0.0001"), d("0.003"), d("18")),
        )
        private val NO_CHARGES = ChargeRates(d("0"), d("0"), d("0"), d("0"), d("0"), d("0"), d("0"), d("0"))
        val ZERO = ChargeModel(NO_CHARGES, NO_CHARGES, NO_CHARGES)
    }
}

/**
 * Simulated margin: percent of notional. Futures use price x lot; short options use strike x lot (a proxy for
 * underlying notional, since uncovered short-option loss is NOT capped at premium).
 */
data class MarginModel(
    val futuresPct: BigDecimal = BigDecimal("12"),
    val shortOptionPct: BigDecimal = BigDecimal("15"),
)

data class ExecutionConfig(
    val maxQuoteAge: Duration = Duration.ofSeconds(15),
    /**
     * Test mode for trying the app while the market is shut: cached and old quotes may fill (at their last price) instead
     * of being refused. Off by default; an invalid price still never fills.
     */
    val allowClosedMarketFills: Boolean = false,
    /** Used only when bid/ask are missing and LTP is the fill reference. */
    val ltpSlippageBps: BigDecimal = BigDecimal.ZERO,
    /** If set, each fill event executes at most this many units/lots, to simulate partial fills. */
    val maxQtyPerFill: Int? = null,
    val charges: ChargeModel = ChargeModel.ILLUSTRATIVE,
    val margin: MarginModel = MarginModel(),
)
