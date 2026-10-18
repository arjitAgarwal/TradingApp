package com.papertrade.app

import com.papertrade.app.data.market.*
import com.papertrade.core.QuoteSource
import com.papertrade.core.Segment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** NOT yet executed: the :app module needs the Android Gradle Plugin to compile (see README "Verification status"). */
class ProviderTest {
    private val fixed = Instant.parse("2026-10-16T05:00:00Z")
    private val demo = DemoProvider(clock = { fixed })

    @Test fun demoQuotesAreAlwaysLabelledDemo() = runBlocking {
        val q = demo.quotes(listOf("NSE:NIFTY50-INDEX", "BSE:SENSEX-INDEX")).getOrThrow()
        assertEquals(2, q.size); assertTrue(q.all { it.source == QuoteSource.DEMO })
        assertEquals(DataMode.DEMO, demo.mode); assertTrue(demo.displayName.contains("DEMO"))
    }
    @Test fun demoIsDeterministicForSameInstant() = runBlocking {
        assertEquals(demo.quotes(listOf("NSE:SBIN-EQ")).getOrThrow()[0].ltp, DemoProvider(clock = { fixed }).quotes(listOf("NSE:SBIN-EQ")).getOrThrow()[0].ltp)
    }
    @Test fun demoChainHasMetadataAndSymmetricStrikes() = runBlocking {
        val c = demo.optionChain(Underlying.NIFTY, null, 5).getOrThrow()
        assertEquals(11, c.rows.size); assertTrue(c.expiries.isNotEmpty())
        c.rows.forEach { r -> assertEquals(Segment.OPTION, r.call!!.info!!.segment); assertTrue(r.call!!.info!!.lotSize > 0) }
    }
}
