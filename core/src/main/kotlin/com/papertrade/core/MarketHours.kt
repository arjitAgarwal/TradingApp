package com.papertrade.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * NSE/BSE regular cash + F&O session: Mon-Fri 09:15-15:30 IST.
 * LIMITATION: exchange holidays and special sessions are NOT known here; a weekday holiday is reported as open.
 * Quotes received while this says closed are tagged CACHED so market orders are refused.
 */
object MarketHours {
    val IST: ZoneId = ZoneId.of("Asia/Kolkata")
    private val OPEN = LocalTime.of(9, 15)
    private val CLOSE = LocalTime.of(15, 30)
    fun isLikelyOpen(at: Instant): Boolean {
        val z = at.atZone(IST)
        if (z.dayOfWeek == DayOfWeek.SATURDAY || z.dayOfWeek == DayOfWeek.SUNDAY) return false
        val t = z.toLocalTime()
        return !t.isBefore(OPEN) && t.isBefore(CLOSE)
    }
}
