package com.papertrade.app.ui

import androidx.compose.ui.graphics.Color
import com.papertrade.app.ui.theme.Gain
import com.papertrade.app.ui.theme.Loss
import com.papertrade.core.MarketHours
import com.papertrade.core.QuoteSource
import com.papertrade.app.data.market.MarketQuote
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

object Fmt {
    private val sym = DecimalFormatSymbols(Locale.US)
    fun inr(v: BigDecimal?, decimals: Int = 2): String = if (v == null) "—" else com.papertrade.core.IndianFormat.group(v, decimals)
    fun rupee(v: BigDecimal?, decimals: Int = 2) = if (v == null) "—" else (if (v.signum() < 0) "-₹" else "₹") + inr(v.abs(), decimals)
    fun signed(v: BigDecimal?, decimals: Int = 2) = if (v == null) "—" else (if (v.signum() > 0) "+" else "") + inr(v, decimals)
    fun pct(v: BigDecimal?) = if (v == null) "—" else (if (v.signum() > 0) "+" else "") + v.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%"
    fun num(v: Long?) = v?.let { inr(BigDecimal(it), 0) } ?: "—"
    fun color(v: BigDecimal?, neutral: Color) = when { v == null || v.signum() == 0 -> neutral; v.signum() > 0 -> Gain; else -> Loss }
    private val timeFmt = DateTimeFormatter.ofPattern("dd MMM, HH:mm:ss").withZone(MarketHours.IST)
    fun time(i: Instant?) = i?.let { timeFmt.format(it) + " IST" } ?: "—"
    private val expiryFmt = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH)
    /** Groww-style label: "NIFTY 19 Oct 22650 Call", "RELIANCE 27 Oct Fut", or the bare ticker for equities. */
    fun label(symbol: String, info: com.papertrade.app.data.market.InstrumentInfo?): String =
        labelParts(symbol, info).let { (main, kind) -> if (kind == null) main else "$main $kind" }
    /** The label split as Groww's watchlist shows it: ("NIFTY 27 Oct 22800", "Call"); kind is null for equities. */
    fun labelParts(symbol: String, info: com.papertrade.app.data.market.InstrumentInfo?): Pair<String, String?> {
        val e = info?.expiry ?: return symbol.substringAfter(':').removeSuffix("-EQ") to null
        val u = info.underlying ?: symbol.substringAfter(':')
        return when (info.optionType) {
            null -> "$u ${expiryFmt.format(e)}" to "Fut"
            else -> "$u ${expiryFmt.format(e)} ${info.strike?.stripTrailingZeros()?.toPlainString() ?: ""}".trimEnd() to
                if (info.optionType == com.papertrade.core.OptionType.CE) "Call" else "Put"
        }
    }
    /** Groww's watchlist change line: signed amount, unsigned percent, e.g. "-133.70 (51.83%)". */
    fun changeLine(change: BigDecimal?, pct: BigDecimal?) = "${signed(change)} (${pct?.abs()?.setScale(2, RoundingMode.HALF_UP)?.toPlainString()?.plus("%") ?: "—"})"
}

enum class Freshness { LIVE, DELAYED, STALE, CACHED, DEMO, NONE }

/** Honest freshness: never labels delayed/cached prices as live. */
fun freshnessOf(q: MarketQuote?, now: Instant, maxAgeSec: Long = 15): Freshness = when {
    q == null -> Freshness.NONE
    q.source == QuoteSource.DEMO -> Freshness.DEMO
    q.source == QuoteSource.CACHED -> Freshness.CACHED
    q.source == QuoteSource.DELAYED -> Freshness.DELAYED
    Duration.between(q.timestamp, now).seconds > maxAgeSec -> Freshness.STALE
    else -> Freshness.LIVE
}
