package com.papertrade.app

import com.papertrade.app.ui.screens.balanceUpdatedAt
import com.papertrade.app.ui.screens.updatedLine
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class AccountPageTest {
    private val now = Instant.parse("2026-11-19T10:13:00Z") // 15:43 IST

    @Test fun `with no cash movement in the last 5 minutes the time is 5 minutes ago`() {
        assertEquals(Instant.parse("2026-11-19T10:08:00Z"), balanceUpdatedAt(listOf(Instant.parse("2026-11-19T04:00:00Z"), Instant.parse("2026-11-18T09:30:00Z")), now))
        assertEquals(Instant.parse("2026-11-19T10:08:00Z"), balanceUpdatedAt(emptyList(), now))
    }

    @Test fun `a trade in the last 5 minutes shows its own time`() {
        val trade = Instant.parse("2026-11-19T10:11:30Z")
        assertEquals(trade, balanceUpdatedAt(listOf(Instant.parse("2026-11-19T04:00:00Z"), trade), now))
    }

    @Test fun `the line is in IST with a lowercase am or pm and the full month`() {
        assertEquals("Last updated at 08:19 am on 19 November 2026", updatedLine(Instant.parse("2026-11-19T02:49:00Z")))
        assertEquals("Last updated at 03:43 pm on 19 November 2026", updatedLine(now))
        assertEquals("Last updated at 12:05 pm on 19 November 2026", updatedLine(Instant.parse("2026-11-19T06:35:00Z")))
        assertEquals("Last updated at 12:05 am on 1 December 2026", updatedLine(Instant.parse("2026-11-30T18:35:00Z")))
    }
}
