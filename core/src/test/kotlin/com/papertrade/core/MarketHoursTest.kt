package com.papertrade.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MarketHoursTest {
    // 2026-10-16 is a Friday. 09:15 IST = 03:45Z, 15:30 IST = 10:00Z
    @Test fun openAndCloseBoundaries() {
        assertFalse(MarketHours.isLikelyOpen(Instant.parse("2026-10-16T03:44:59Z")))
        assertTrue(MarketHours.isLikelyOpen(Instant.parse("2026-10-16T03:45:00Z")))
        assertTrue(MarketHours.isLikelyOpen(Instant.parse("2026-10-16T09:59:59Z")))
        assertFalse(MarketHours.isLikelyOpen(Instant.parse("2026-10-16T10:00:00Z")))
    }
    @Test fun weekendIsClosed() {
        assertFalse(MarketHours.isLikelyOpen(Instant.parse("2026-10-17T06:00:00Z"))) // Saturday
    }
}
