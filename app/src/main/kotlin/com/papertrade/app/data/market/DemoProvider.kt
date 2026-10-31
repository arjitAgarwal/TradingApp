package com.papertrade.app.data.market

import com.papertrade.core.OptionType
import com.papertrade.core.QuoteSource
import com.papertrade.core.Segment
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * DEVELOPMENT / DEMONSTRATION ONLY. Prices are synthetic and are NOT real market data.
 * Every quote carries QuoteSource.DEMO and the UI shows a permanent banner while this provider is active.
 * Deterministic: price is a pure function of (symbol, time), no stored RNG state.
 */
class DemoProvider(private val clock: () -> Instant = { Instant.now() }, private val zone: java.time.ZoneId = com.papertrade.core.MarketHours.IST) : MarketDataProvider {
    override val id = "demo"
    override val displayName = "DEMO DATA (synthetic, not real prices)"
    override val mode = DataMode.DEMO
    override val feedStatus: StateFlow<FeedStatus> = MutableStateFlow(FeedStatus(FeedState.CONNECTED, "Demo feed", null))

    private val equities = mapOf(
        "NSE:RELIANCE-EQ" to ("Reliance Industries" to 2900.0), "NSE:TCS-EQ" to ("Tata Consultancy Services" to 4100.0),
        "NSE:INFY-EQ" to ("Infosys" to 1800.0), "NSE:HDFCBANK-EQ" to ("HDFC Bank" to 1700.0), "NSE:SBIN-EQ" to ("State Bank of India" to 800.0),
    )
    private val bases = mapOf("NSE:NIFTY50-INDEX" to 25000.0, "BSE:SENSEX-INDEX" to 82000.0) + equities.mapValues { it.value.second }
    private val lot = mapOf(Underlying.NIFTY to 75, Underlying.SENSEX to 20)
    private val step = mapOf(Underlying.NIFTY to 50, Underlying.SENSEX to 100)
    private val ymd = DateTimeFormatter.ofPattern("yyMMdd")

    private fun bd(v: Double, scale: Int = 2) = BigDecimal(v).setScale(scale, RoundingMode.HALF_EVEN)
    private fun tick(v: Double) = BigDecimal(Math.round(v * 20.0) / 20.0).setScale(2, RoundingMode.HALF_EVEN)

    private fun spot(base: Double, key: String, at: Instant): Double {
        val t = at.epochSecond.toDouble(); val ph = (key.hashCode() % 628) / 100.0
        return base * (1 + 0.006 * sin(t / 300.0 + ph) + 0.002 * sin(t / 37.0 + ph * 2))
    }

    private fun quoteFor(symbol: String, p: Double, base: Double, at: Instant, bidAsk: Boolean = true): MarketQuote {
        val ltp = tick(p); val prev = tick(base)
        return MarketQuote(symbol, ltp, open = prev, high = tick(max(p, base) * 1.001), low = tick(minOf(p, base) * 0.999), prevClose = prev,
            change = ltp - prev, changePct = ((ltp - prev) * BigDecimal(100)).divide(prev, 2, RoundingMode.HALF_EVEN),
            bid = if (bidAsk) ltp - BigDecimal("0.05") else null, ask = if (bidAsk) ltp + BigDecimal("0.05") else null,
            timestamp = at, source = QuoteSource.DEMO)
    }

    /** The next [n] weekly expiries, today included: NIFTY expires on Tuesdays, SENSEX on Thursdays (holidays ignored). */
    private fun nextExpiries(u: Underlying, n: Int): List<LocalDate> {
        val day = when (u) { Underlying.NIFTY -> DayOfWeek.TUESDAY; Underlying.SENSEX -> DayOfWeek.THURSDAY }
        var d = clock().atZone(zone).toLocalDate(); val out = mutableListOf<LocalDate>()
        while (out.size < n) { if (d.dayOfWeek == day) out += d; d = d.plusDays(1) }
        return out
    }

    private fun optSymbol(u: Underlying, e: LocalDate, strike: Int, t: OptionType) = "DEMO:${u.name}${e.format(ymd)}$strike${t.name}"
    private fun futSymbol(u: Underlying, e: LocalDate) = "DEMO:${u.name}${e.format(ymd)}FUT"

    private fun optionPrice(spot: Double, strike: Int, t: OptionType): Double {
        val intrinsic = if (t == OptionType.CE) max(0.0, spot - strike) else max(0.0, strike - spot)
        return max(0.5, intrinsic + spot * 0.004 * exp(-abs(spot - strike) / (spot * 0.02)))
    }

    private fun optionInfo(u: Underlying, e: LocalDate, strike: Int, t: OptionType) = InstrumentInfo(
        optSymbol(u, e, strike, t), "${u.displayName} $strike ${t.name} $e (demo)", Segment.OPTION, lot.getValue(u), BigDecimal("0.05"), e, BigDecimal(strike), t, u.name)

    override suspend fun quotes(symbols: List<String>): Result<List<MarketQuote>> = runCatching {
        val now = clock()
        symbols.mapNotNull { s ->
            when {
                bases.containsKey(s) -> quoteFor(s, spot(bases.getValue(s), s, now), bases.getValue(s), now)
                s.startsWith("DEMO:") -> demoDerivativeQuote(s, now)
                else -> null
            }
        }
    }

    private fun demoDerivativeQuote(s: String, now: Instant): MarketQuote? {
        val u = Underlying.values().firstOrNull { s.startsWith("DEMO:${it.name}") } ?: return null
        return quoteFor(s, derivativePrice(s, u, spot(bases.getValue(u.indexSymbol), u.indexSymbol, now)), derivativePrice(s, u, bases.getValue(u.indexSymbol)), now)
    }

    /** A demo future or option priced off the given index level. */
    private fun derivativePrice(s: String, u: Underlying, index: Double): Double {
        if (s.endsWith("FUT")) return index * 1.002
        val body = s.removePrefix("DEMO:${u.name}"); val strike = body.substring(6, body.length - 2).toInt()
        return optionPrice(index, strike, if (s.endsWith("CE")) OptionType.CE else OptionType.PE)
    }

    /** Synthetic price of any demo symbol at [at]; null for symbols this provider doesn't know. */
    private fun priceAt(s: String, at: Instant): Double? = bases[s]?.let { spot(it, s, at) }
        ?: Underlying.values().firstOrNull { s.startsWith("DEMO:${it.name}") }?.let { u -> derivativePrice(s, u, spot(bases.getValue(u.indexSymbol), u.indexSymbol, at)) }

    override suspend fun searchEquities(query: String) = runCatching {
        equities.filter { it.key.contains(query, true) || it.value.first.contains(query, true) }.map { (s, v) -> info(s, v.first) }
    }
    private fun info(s: String, name: String) = InstrumentInfo(s, name, Segment.EQUITY, 1, BigDecimal("0.05"))
    override suspend fun equityInfo(symbol: String) = runCatching { info(symbol, equities.getValue(symbol).first) }

    override suspend fun futures(underlying: Underlying) = runCatching {
        val now = clock()
        nextExpiries(underlying, 3).map { e ->
            val i = InstrumentInfo(futSymbol(underlying, e), "${underlying.displayName} FUT $e (demo)", Segment.FUTURES, lot.getValue(underlying), BigDecimal("0.05"), e, underlying = underlying.name)
            FutureContract(i, demoDerivativeQuote(i.symbol, now))
        }
    }

    override suspend fun optionChain(underlying: Underlying, expiry: LocalDate?, strikesEachSide: Int) = runCatching {
        val now = clock(); val expiries = nextExpiries(underlying, 3); val e = expiry ?: expiries.first()
        val spot = spot(bases.getValue(underlying.indexSymbol), underlying.indexSymbol, now)
        val st = step.getValue(underlying); val atm = (Math.round(spot / st) * st).toInt()
        val rows = (-strikesEachSide..strikesEachSide).map { k ->
            val strike = atm + k * st
            fun side(t: OptionType): OptionSide {
                val sym = optSymbol(underlying, e, strike, t)
                return OptionSide(optionInfo(underlying, e, strike, t), sym, demoDerivativeQuote(sym, now)!!.copy(volume = 1000L + abs(k) * 10, openInterest = 50_000L / (1 + abs(k))))
            }
            OptionRow(BigDecimal(strike), side(OptionType.CE), side(OptionType.PE))
        }
        OptionChain(underlying, tick(spot), expiries, e, rows, now, QuoteSource.DEMO)
    }

    override suspend fun candles(symbol: String, interval: CandleInterval) = runCatching {
        val price: (Instant) -> Double = if (priceAt(symbol, clock()) != null) { t -> priceAt(symbol, t)!! }
            else { t -> spot(bases.getValue(Underlying.NIFTY.indexSymbol), symbol, t) }
        val stepSec = when (interval) { CandleInterval.M1 -> 60L; CandleInterval.M5 -> 300; CandleInterval.M15 -> 900; CandleInterval.H1 -> 3600; CandleInterval.D1 -> 86400 }
        val end = clock().epochSecond / stepSec * stepSec
        (80 downTo 1).map { i ->
            val t0 = Instant.ofEpochSecond(end - i * stepSec); val t1 = Instant.ofEpochSecond(end - (i - 1) * stepSec)
            val o = price(t0); val c = price(t1)
            Candle(t0, tick(o), tick(max(o, c) * 1.0005), tick(minOf(o, c) * 0.9995), tick(c), 1000)
        }
    }

    override fun quoteStream(symbols: Set<String>): Flow<MarketQuote> = flow {
        while (true) { quotes(symbols.toList()).getOrNull()?.forEach { emit(it) }; delay(1000) }
    }
}
