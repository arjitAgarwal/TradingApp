package com.papertrade.app

import com.papertrade.app.data.market.UpstoxFeed
import com.papertrade.core.QuoteSource
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.Instant

/** Upstox V3 feed decoding, against messages built field by field from MarketDataFeedV3.proto. */
class UpstoxFeedTest {
    /** Tiny protobuf writer, just enough to build test messages. */
    private class W {
        val out = ByteArrayOutputStream()
        fun varint(v: Long) { var x = v; while (x and 0x7FL.inv() != 0L) { out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }; out.write(x.toInt()) }
        fun tag(field: Int, wire: Int) = varint((field shl 3 or wire).toLong())
        fun double(field: Int, v: Double) { tag(field, 1); val b = java.lang.Double.doubleToLongBits(v); for (i in 0 until 8) out.write(((b ushr (8 * i)) and 0xFF).toInt()) }
        fun int64(field: Int, v: Long) { tag(field, 0); varint(v) }
        fun bytes(field: Int, b: ByteArray) { tag(field, 2); varint(b.size.toLong()); out.write(b) }
        fun string(field: Int, s: String) = bytes(field, s.toByteArray())
        fun msg(field: Int, build: W.() -> Unit) = bytes(field, W().apply(build).out.toByteArray())
    }

    private fun response(build: W.() -> Unit) = W().apply { int64(1, 1); build(); int64(3, 1_760_000_000_000) }.out.toByteArray()
    private fun W.feed(key: String, build: W.() -> Unit) = msg(2) { string(1, key); msg(2, build) }

    @Test fun decodesOptionFullFeed() {
        val bytes = response {
            feed("NSE_FO|51326") {
                msg(2) { msg(1) { // fullFeed.marketFF
                    msg(1) { double(1, 224.25); int64(2, 1_760_000_000_000); int64(3, 65); double(4, 180.5) } // ltpc
                    msg(2) { msg(1) { int64(1, 650); double(2, 224.2); int64(3, 130); double(4, 224.3) }; msg(1) { int64(1, 1); double(2, 224.0); int64(3, 1); double(4, 224.5) } }
                    msg(4) { msg(1) { string(1, "1d"); double(2, 200.0); double(3, 230.0); double(4, 190.0); double(5, 224.25); int64(6, 1000) }
                             msg(1) { string(1, "I1"); double(2, 1.0); double(3, 2.0); double(4, 0.5) } }
                    double(5, 210.0); int64(6, 123_456); double(7, 98_765.0)
                } }
            }
        }
        val t = UpstoxFeed.decode(bytes).getValue("NSE_FO|51326")
        assertEquals(224.25, t.ltp!!, 0.0); assertEquals(180.5, t.prevClose!!, 0.0)
        assertEquals(224.2, t.bid!!, 0.0); assertEquals(224.3, t.ask!!, 0.0) // best level, not the second
        assertEquals(200.0, t.open!!, 0.0); assertEquals(230.0, t.high!!, 0.0); assertEquals(190.0, t.low!!, 0.0) // "1d", not "I1"
        assertEquals(123_456L, t.volume); assertEquals(98_765.0, t.oi!!, 0.0)
    }

    @Test fun decodesIndexAndLtpcFeedsTogether() {
        val bytes = response {
            feed("NSE_INDEX|Nifty 50") { msg(2) { msg(2) { msg(1) { double(1, 22_594.25); double(4, 22_811.35) } } } } // fullFeed.indexFF
            feed("NSE_FO|47129") { msg(1) { double(1, 55.0) } } // plain ltpc mode, no close price
        }
        val ticks = UpstoxFeed.decode(bytes)
        assertEquals(22_594.25, ticks.getValue("NSE_INDEX|Nifty 50").ltp!!, 0.0)
        assertEquals(22_811.35, ticks.getValue("NSE_INDEX|Nifty 50").prevClose!!, 0.0)
        assertEquals(55.0, ticks.getValue("NSE_FO|47129").ltp!!, 0.0)
        assertNull(ticks.getValue("NSE_FO|47129").prevClose)
    }

    @Test fun marketInfoMessageHasNoTicks() {
        val bytes = W().apply { int64(1, 2); msg(4) { msg(1) { string(1, "NSE_FO"); int64(2, 3) } } }.out.toByteArray()
        assertTrue(UpstoxFeed.decode(bytes).isEmpty())
    }

    @Test fun mergeKeepsFieldsTheTickLacks() {
        val now = Instant.parse("2026-10-22T04:30:00Z")
        val first = UpstoxFeed.merge(null, "NSE:X", UpstoxFeed.Tick(ltp = 100.0, prevClose = 80.0, bid = 99.95, ask = 100.05, volume = 10), now, QuoteSource.LIVE)!!
        val next = UpstoxFeed.merge(first, "NSE:X", UpstoxFeed.Tick(ltp = 101.0), now.plusSeconds(1), QuoteSource.LIVE)!!
        assertEquals(BigDecimal("101.00"), next.ltp)
        assertEquals(BigDecimal("80.00"), next.prevClose)
        assertEquals(BigDecimal("21.00"), next.change); assertEquals(BigDecimal("26.25"), next.changePct)
        assertEquals(BigDecimal("99.95"), next.bid); assertEquals(10L, next.volume)
        assertNull(UpstoxFeed.merge(null, "NSE:Y", UpstoxFeed.Tick(bid = 1.0), now, QuoteSource.LIVE)) // no price yet: nothing to show
    }

    @Test fun subscribeRequestShape() {
        val s = UpstoxFeed.subscribeRequest(listOf("NSE_INDEX|Nifty 50", "NSE_FO|51326"))
        assertTrue(s.contains("\"method\":\"sub\""))
        assertTrue(s.contains("\"mode\":\"full\""))
        assertTrue(s.contains("\"instrumentKeys\":[\"NSE_INDEX|Nifty 50\",\"NSE_FO|51326\"]"))
    }
}
