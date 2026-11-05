package com.papertrade.app

import com.papertrade.app.data.market.*
import com.papertrade.app.ui.ExpiryCache
import com.papertrade.app.ui.IndexExpiries
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class IndexExpiriesLoaderTest {
    private val day1 = LocalDate.of(2026, 11, 5); private val dates = listOf(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 12))
    private fun ok(): Result<List<LocalDate>> = Result.success(dates)
    private fun fail(kind: ProviderException.Kind): Result<List<LocalDate>> = Result.failure(ProviderException("nope", kind))

    private class Fake(override val id: String, val answer: suspend (Underlying, Int) -> Result<List<LocalDate>>) : MarketDataProvider by DemoProvider() {
        val calls = mutableMapOf<Underlying, Int>()
        override suspend fun optionExpiries(underlying: Underlying): Result<List<LocalDate>> {
            val n = (calls[underlying] ?: 0) + 1; calls[underlying] = n; return answer(underlying, n)
        }
    }
    private class MemCache : ExpiryCache {
        val m = mutableMapOf<String, List<LocalDate>>()
        override fun read(provider: String, u: Underlying) = m["$provider.$u"].orEmpty()
        override fun write(provider: String, u: Underlying, dates: List<LocalDate>) { m["$provider.$u"] = dates }
    }
    private inner class Rig(first: MarketDataProvider) {
        val provider = MutableStateFlow(first); val day = MutableStateFlow(day1); val login = MutableStateFlow(0); val active = MutableStateFlow(true); val cache = MemCache()
        val logs = mutableListOf<String>()
        val loader = IndexExpiries(provider, day, login, active, cache, { logs += it })
        fun start(scope: TestScope) = scope.backgroundScope.launch { loader.run() }
    }

    @Test fun `failed read retries until it succeeds`() = runTest {
        val p = Fake("f") { _, n -> if (n < 3) fail(ProviderException.Kind.NETWORK) else ok() }; val r = Rig(p); r.start(this)
        runCurrent(); assertTrue(r.loader.dates.value.isEmpty())
        advanceTimeBy(5_001); assertTrue("second try also failed", r.loader.dates.value.isEmpty())
        advanceTimeBy(15_001); assertEquals(dates, r.loader.dates.value[Underlying.NIFTY])
        assertEquals(3, p.calls[Underlying.NIFTY]); assertTrue("failures are logged", r.logs.any { it.contains("attempt 1 failed: nope") })
    }

    @Test fun `one index failing doesn't hold up the other`() = runTest {
        val p = Fake("f") { u, _ -> if (u == Underlying.NIFTY) fail(ProviderException.Kind.NETWORK) else ok() }; val r = Rig(p); r.start(this)
        runCurrent(); assertEquals(setOf(Underlying.SENSEX), r.loader.dates.value.keys)
    }

    @Test fun `both indexes are read at the same time`() = runTest {
        val gate = CompletableDeferred<Unit>(); val p = Fake("f") { _, _ -> gate.await(); ok() }; val r = Rig(p); r.start(this)
        runCurrent(); assertEquals(1, p.calls[Underlying.NIFTY]); assertEquals("SENSEX didn't wait for NIFTY", 1, p.calls[Underlying.SENSEX]); gate.complete(Unit)
    }

    @Test fun `a login problem waits for the login instead of retrying`() = runTest {
        var loggedIn = false; val p = Fake("f") { _, _ -> if (loggedIn) ok() else fail(ProviderException.Kind.NOT_CONFIGURED) }; val r = Rig(p); r.start(this)
        advanceTimeBy(600_000); assertEquals(1, p.calls[Underlying.NIFTY]); assertTrue(r.loader.dates.value.isEmpty())
        loggedIn = true; r.login.value = 1; runCurrent(); assertEquals(dates, r.loader.dates.value[Underlying.NIFTY])
    }

    @Test fun `rate limiting waits at least a minute`() = runTest {
        val p = Fake("f") { _, _ -> fail(ProviderException.Kind.RATE_LIMIT) }; val r = Rig(p); r.start(this)
        advanceTimeBy(59_000); assertEquals(1, p.calls[Underlying.NIFTY]); advanceTimeBy(2_000); assertEquals(2, p.calls[Underlying.NIFTY])
    }

    @Test fun `saved dates show before the read returns`() = runTest {
        val gate = CompletableDeferred<Unit>(); val p = Fake("f") { _, _ -> gate.await(); ok() }; val r = Rig(p)
        val saved = listOf(LocalDate.of(2026, 11, 10)); r.cache.write("f", Underlying.NIFTY, saved); r.start(this)
        runCurrent(); assertEquals(saved, r.loader.dates.value[Underlying.NIFTY])
        gate.complete(Unit); runCurrent(); assertEquals(dates, r.loader.dates.value[Underlying.NIFTY]); assertEquals(dates, r.cache.read("f", Underlying.NIFTY))
    }

    @Test fun `a late answer from the old provider is dropped`() = runTest {
        val slow = Fake("old") { _, _ -> kotlinx.coroutines.delay(10_000); ok() }; val next = listOf(LocalDate.of(2026, 12, 1))
        val r = Rig(slow); r.start(this); runCurrent()
        r.provider.value = Fake("new") { _, _ -> Result.success(next) }; advanceTimeBy(60_000)
        assertEquals(next, r.loader.dates.value[Underlying.NIFTY]); assertTrue("old provider's dates weren't saved", r.cache.read("old", Underlying.NIFTY).isEmpty())
    }

    @Test fun `a provider's dates are cleared when switching to one with none saved`() = runTest {
        val r = Rig(Fake("a") { _, _ -> ok() }); r.start(this); runCurrent(); assertFalse(r.loader.dates.value.isEmpty())
        r.provider.value = Fake("b") { _, _ -> fail(ProviderException.Kind.NOT_CONFIGURED) }; runCurrent(); assertTrue(r.loader.dates.value.isEmpty())
    }

    @Test fun `reads once a day and keeps the old dates if the new read fails`() = runTest {
        var failing = false; val p = Fake("f") { _, _ -> if (failing) fail(ProviderException.Kind.NETWORK) else ok() }; val r = Rig(p); r.start(this)
        runCurrent(); assertEquals(1, p.calls[Underlying.NIFTY])
        r.login.value = 1; runCurrent(); assertEquals("already loaded today", 1, p.calls[Underlying.NIFTY])
        failing = true; r.day.value = day1.plusDays(1); runCurrent()
        assertEquals(2, p.calls[Underlying.NIFTY]); assertEquals(dates, r.loader.dates.value[Underlying.NIFTY])
    }

    @Test fun `no reads or retries in the background`() = runTest {
        val p = Fake("f") { _, _ -> fail(ProviderException.Kind.NETWORK) }; val r = Rig(p); r.start(this); runCurrent(); assertEquals(1, p.calls[Underlying.NIFTY])
        r.active.value = false; advanceTimeBy(600_000); assertEquals(1, p.calls[Underlying.NIFTY])
        r.active.value = true; runCurrent(); assertEquals("coming back tries again", 2, p.calls[Underlying.NIFTY])
    }

    @Test fun `an empty list counts as a failure`() = runTest {
        val p = Fake("f") { _, n -> if (n == 1) Result.success(emptyList()) else ok() }; val r = Rig(p); r.start(this)
        runCurrent(); assertTrue(r.loader.dates.value.isEmpty()); advanceTimeBy(5_001); assertEquals(dates, r.loader.dates.value[Underlying.NIFTY])
    }

    @Test fun `demo's default reads the chain`() = runTest {
        assertTrue(DemoProvider().optionExpiries(Underlying.NIFTY).getOrThrow().isNotEmpty())
    }
}
