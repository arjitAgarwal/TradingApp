package com.papertrade.app.data.market

import java.math.BigDecimal

/**
 * Decoder for Upstox's Market Data Feed V3 messages (protobuf, MarketDataFeedV3.proto). Only the fields this app uses are
 * read; everything else is skipped by wire type, so new fields Upstox adds don't break it. Written by hand to avoid a
 * protobuf dependency for a handful of fields.
 *
 *   FeedResponse { type=1 enum; feeds=2 map<string, Feed>; currentTs=3 int64; marketInfo=4 }
 *   Feed { ltpc=1 LTPC; fullFeed=2 FullFeed; firstLevelWithGreeks=3; requestMode=4 }
 *   FullFeed { marketFF=1 MarketFullFeed; indexFF=2 IndexFullFeed }
 *   MarketFullFeed { ltpc=1; marketLevel=2 MarketLevel; optionGreeks=3; marketOHLC=4 MarketOHLC; atp=5 double; vtt=6 int64; oi=7 double; ... }
 *   IndexFullFeed { ltpc=1; marketOHLC=2 }
 *   LTPC { ltp=1 double; ltt=2 int64; ltq=3 int64; cp=4 double }
 *   MarketLevel { bidAskQuote=1 repeated Quote }   Quote { bidQ=1 int64; bidP=2 double; askQ=3 int64; askP=4 double }
 *   MarketOHLC { ohlc=1 repeated OHLC }            OHLC { interval=1 string; open=2; high=3; low=4; close=5 double; vol=6; ts=7 int64 }
 */
internal object UpstoxFeed {
    /** One instrument's update. Null = not present in this message (proto3 omits zero values), so keep the previous value. */
    data class Tick(
        val ltp: Double? = null, val prevClose: Double? = null, val bid: Double? = null, val ask: Double? = null,
        val open: Double? = null, val high: Double? = null, val low: Double? = null, val volume: Long? = null, val oi: Double? = null,
    )

    /** Instrument key ("NSE_FO|12345") to tick, for every instrument in one message. Market-info messages give an empty map. */
    fun decode(bytes: ByteArray): Map<String, Tick> {
        val out = HashMap<String, Tick>()
        Proto(bytes).forEach { f, r -> if (f == 2) mapEntry(r.message())?.let { (k, t) -> out[k] = t } }
        return out
    }

    private fun mapEntry(p: Proto): Pair<String, Tick>? {
        var key: String? = null; var tick = Tick()
        p.forEach { f, e -> when (f) { 1 -> key = e.string(); 2 -> tick = feed(e.message()) } }
        return key?.let { it to tick }
    }

    private fun feed(p: Proto): Tick {
        var t = Tick()
        p.forEach { f, e -> when (f) {
            1 -> t = ltpc(e.message(), t)
            2 -> e.message().forEach { ff, fe -> when (ff) { 1 -> t = marketFull(fe.message(), t); 2 -> t = indexFull(fe.message(), t) } }
        } }
        return t
    }

    private fun marketFull(p: Proto, t0: Tick): Tick {
        var t = t0; var bestSeen = false
        p.forEach { f, e -> when (f) {
            1 -> t = ltpc(e.message(), t)
            2 -> e.message().forEach { lf, le -> if (lf == 1 && !bestSeen) { bestSeen = true; t = quote(le.message(), t) } } // best level only
            4 -> t = dayOhlc(e.message(), t)
            6 -> t = t.copy(volume = e.varint())
            7 -> t = t.copy(oi = e.double())
        } }
        return t
    }

    private fun indexFull(p: Proto, t0: Tick): Tick {
        var t = t0
        p.forEach { f, e -> when (f) { 1 -> t = ltpc(e.message(), t); 2 -> t = dayOhlc(e.message(), t) } }
        return t
    }

    private fun ltpc(p: Proto, t0: Tick): Tick {
        var t = t0
        p.forEach { f, e -> when (f) { 1 -> t = t.copy(ltp = e.double()); 4 -> t = t.copy(prevClose = e.double()) } }
        return t
    }

    private fun quote(p: Proto, t0: Tick): Tick {
        var t = t0
        p.forEach { f, e -> when (f) { 2 -> t = t.copy(bid = e.double()); 4 -> t = t.copy(ask = e.double()) } }
        return t
    }

    /** Today's open/high/low from the "1d" candle; the "I1" (1-minute) candles are ignored. */
    private fun dayOhlc(p: Proto, t0: Tick): Tick {
        var t = t0
        p.forEach { f, e ->
            if (f != 1) return@forEach
            var interval: String? = null; var o: Double? = null; var h: Double? = null; var l: Double? = null
            e.message().forEach { cf, ce -> when (cf) { 1 -> interval = ce.string(); 2 -> o = ce.double(); 3 -> h = ce.double(); 4 -> l = ce.double() } }
            if (interval == "1d") t = t.copy(open = o, high = h, low = l)
        }
        return t
    }

    /** The subscribe request. Upstox V3 expects it as a BINARY frame holding this JSON. */
    fun subscribeRequest(keys: Collection<String>, mode: String = "full"): String =
        """{"guid":"pt-${System.nanoTime()}","method":"sub","data":{"mode":"$mode","instrumentKeys":[${keys.joinToString(",") { "\"$it\"" }}]}}"""

    /** Merges a tick into the previous quote for [symbol]. Fields the tick lacks keep their previous value. */
    fun merge(prev: MarketQuote?, symbol: String, t: Tick, now: java.time.Instant, source: com.papertrade.core.QuoteSource): MarketQuote? {
        fun d(v: Double?) = v?.let { BigDecimal.valueOf(it).setScale(2, java.math.RoundingMode.HALF_UP) }
        val ltp = d(t.ltp) ?: prev?.ltp ?: return null
        val close = d(t.prevClose)?.takeIf { it.signum() > 0 } ?: prev?.prevClose
        val ch = close?.let { ltp - it }
        val pct = if (ch != null && close.signum() != 0) ch.multiply(BigDecimal(100)).divide(close, 2, java.math.RoundingMode.HALF_UP) else null
        return MarketQuote(symbol, ltp, d(t.open) ?: prev?.open, d(t.high) ?: prev?.high, d(t.low) ?: prev?.low, close, ch, pct,
            d(t.bid)?.takeIf { it.signum() > 0 } ?: prev?.bid, d(t.ask)?.takeIf { it.signum() > 0 } ?: prev?.ask,
            t.volume ?: prev?.volume, t.oi?.toLong()?.takeIf { it > 0 } ?: prev?.openInterest, prev?.impliedVol, now, source)
    }
}

/** Minimal protobuf wire-format reader over [buf] from [start] to [end]. */
internal class Proto(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {
    /** The current field's value; valid only inside a [forEach] callback. */
    inner class Field internal constructor(private val wire: Int, private val start: Int, private val len: Int, private val varintValue: Long) {
        fun varint() = varintValue
        fun double(): Double = if (wire == 1) java.lang.Double.longBitsToDouble(le64(start)) else varintValue.toDouble()
        fun message() = Proto(buf, start, start + len)
        fun string() = String(buf, start, len, Charsets.UTF_8)
    }

    fun forEach(action: (field: Int, value: Field) -> Unit) {
        while (pos < end) {
            val tag = readVarint(); val field = (tag ushr 3).toInt(); val wire = (tag and 7).toInt()
            val value = when (wire) {
                0 -> Field(0, pos, 0, readVarint())
                1 -> Field(1, pos, 8, 0).also { pos += 8 }
                2 -> { val len = readVarint().toInt(); require(len >= 0 && pos + len <= end) { "bad length" }; Field(2, pos, len, 0).also { pos += len } }
                5 -> Field(5, pos, 4, 0).also { pos += 4 }
                else -> throw IllegalArgumentException("unsupported wire type $wire")
            }
            action(field, value)
        }
    }

    private fun readVarint(): Long {
        var result = 0L; var shift = 0
        while (true) {
            require(pos < end) { "truncated varint" }
            val b = buf[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7; require(shift < 64) { "varint too long" }
        }
    }

    private fun le64(at: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (buf[at + i].toLong() and 0xFF)
        return v
    }
}
