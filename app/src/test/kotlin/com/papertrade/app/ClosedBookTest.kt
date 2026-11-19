package com.papertrade.app

import com.papertrade.app.ui.screens.closedBook
import com.papertrade.core.Fill
import com.papertrade.core.MarketHours
import com.papertrade.core.QuoteSource
import com.papertrade.core.Side
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class ClosedBookTest {
    private var n = 0
    private fun ist(d: LocalDate, h: Int, m: Int, s: Int = 0): Instant = LocalDateTime.of(d, LocalTime.of(h, m, s)).atZone(MarketHours.IST).toInstant()
    private fun fill(side: Side, lots: Int, price: String, at: Instant) =
        Fill("F${++n}", "O$n", "NSE:NIFTY24900CE", side, lots, BigDecimal(price), BigDecimal.ZERO, BigDecimal.ZERO, at, QuoteSource.LIVE)
    private fun buy(lots: Int, price: String, at: Instant) = fill(Side.BUY, lots, price, at)
    private fun sell(lots: Int, price: String, at: Instant) = fill(Side.SELL, lots, price, at)
    private fun money(v: BigDecimal?) = v!!.setScale(2, RoundingMode.HALF_UP).toPlainString()

    private val nov14 = LocalDate.of(2026, 11, 14); private val nov16 = LocalDate.of(2026, 11, 16); private val nov17 = LocalDate.of(2026, 11, 17)

    @Test fun `two buys and a sell on the same day average the buys`() {
        val day = LocalDate.of(2026, 11, 19)
        val b = closedBook(listOf(buy(1, "665.85", ist(day, 13, 1, 0)), buy(1, "680.15", ist(day, 13, 1, 27)), sell(2, "771.50", ist(day, 13, 2, 51))), day)
        assertEquals(2, b.first); assertEquals("673.00", money(b.second)); assertEquals("771.50", money(b.third))
    }

    @Test fun `lots carried in from an earlier day count at their average cost, not just today's buy`() {
        // 11 lots bought on the 14th; on the 17th: sell 1, buy 1 at 486.40, sell the other 11. Before the fix the card said Buy ₹486.40.
        val b = closedBook(listOf(buy(11, "400.00", ist(nov14, 10, 0)), sell(1, "480.75", ist(nov17, 10, 41, 49)), buy(1, "486.40", ist(nov17, 10, 42, 4)),
            sell(11, "470.00", ist(nov17, 12, 38, 59))), nov17)
        assertEquals(12, b.first); assertEquals("407.20", money(b.second)); assertEquals("470.90", money(b.third))
    }

    @Test fun `a sale on an earlier day leaves the rest at the same average cost`() {
        // 10 at 50, 5 sold the next day (5 left at 50), then 5 more at 100 and all 10 sold today: bought at 75 on average.
        val b = closedBook(listOf(buy(10, "50", ist(nov14, 10, 0)), sell(5, "60", ist(nov16, 10, 0)), buy(5, "100", ist(nov17, 10, 0)), sell(10, "110", ist(nov17, 11, 0))), nov17)
        assertEquals(10, b.first); assertEquals("75.00", money(b.second)); assertEquals("110.00", money(b.third))
    }

    @Test fun `a short carried in counts as sold at its average`() {
        val b = closedBook(listOf(sell(2, "100", ist(nov16, 10, 0)), buy(2, "80", ist(nov17, 10, 0))), nov17)
        assertEquals(2, b.first); assertEquals("80.00", money(b.second)); assertEquals("100.00", money(b.third))
    }

    @Test fun `a round closed on an earlier day is left out`() {
        val b = closedBook(listOf(buy(1, "50", ist(nov16, 10, 0)), sell(1, "60", ist(nov16, 11, 0)), buy(1, "70", ist(nov17, 10, 0)), sell(1, "80", ist(nov17, 11, 0))), nov17)
        assertEquals(1, b.first); assertEquals("70.00", money(b.second)); assertEquals("80.00", money(b.third))
    }

    @Test fun `days are split at IST midnight, in any order`() {
        // 18:00 UTC on the 16th is 23:30 IST (carried in); 19:00 UTC is 00:30 IST on the 17th (today).
        val b = closedBook(listOf(sell(2, "150", Instant.parse("2026-11-17T05:00:00Z")), buy(1, "200", Instant.parse("2026-11-16T19:00:00Z")),
            buy(1, "100", Instant.parse("2026-11-16T18:00:00Z"))), nov17)
        assertEquals(2, b.first); assertEquals("150.00", money(b.second)); assertEquals("150.00", money(b.third))
    }
}
