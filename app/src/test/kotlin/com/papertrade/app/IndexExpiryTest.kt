package com.papertrade.app

import com.papertrade.app.ui.screens.nextIndexExpiry
import com.papertrade.core.MarketHours
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class IndexExpiryTest {
    private val thu = LocalDate.of(2026, 11, 5); private val nextThu = LocalDate.of(2026, 11, 12)
    private val list = listOf(thu, nextThu)
    private fun ist(d: LocalDate, h: Int, m: Int, s: Int = 0): Instant = LocalDateTime.of(d, java.time.LocalTime.of(h, m, s)).atZone(MarketHours.IST).toInstant()

    @Test fun `today's expiry holds until the close`() { assertEquals(thu, nextIndexExpiry(list, ist(thu, 15, 29, 59))) }
    @Test fun `after the close it moves to the next expiry`() {
        assertEquals(nextThu, nextIndexExpiry(list, ist(thu, 15, 30)))
        assertEquals(nextThu, nextIndexExpiry(list, ist(thu, 23, 59)))
    }
    @Test fun `a list loaded days ago never shows a past date`() { assertEquals(nextThu, nextIndexExpiry(list, ist(thu.plusDays(1), 0, 0))) }
    @Test fun `unsorted lists still give the nearest`() { assertEquals(thu, nextIndexExpiry(listOf(nextThu, thu), ist(thu, 10, 0))) }
    @Test fun `nothing left gives no chip`() { assertNull(nextIndexExpiry(list, ist(nextThu, 16, 0))); assertNull(nextIndexExpiry(emptyList(), ist(thu, 10, 0))) }
    @Test fun `the cut-off is IST whatever the device zone`() {
        // 10:00 UTC is 15:30 IST.
        assertEquals(nextThu, nextIndexExpiry(list, Instant.parse("2026-11-05T10:00:00Z")))
        assertEquals(thu, nextIndexExpiry(list, Instant.parse("2026-11-05T09:59:59Z")))
    }
}
