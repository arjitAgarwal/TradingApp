package com.papertrade.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.papertrade.app.data.db.AppDatabase
import com.papertrade.app.data.market.InstrumentInfo
import com.papertrade.app.data.market.MarketQuote
import com.papertrade.app.data.repo.AppSettings
import com.papertrade.app.data.repo.TradingRepository
import com.papertrade.core.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.Instant

/** Instrumented (needs an emulator/device). Uses deterministic TEST-source quotes, never live data. */
@RunWith(AndroidJUnit4::class)
class TradingDatabaseTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val name = "test-${System.nanoTime()}.db"
    private lateinit var db: AppDatabase
    private lateinit var repo: TradingRepository
    private val info = InstrumentInfo("NSE:TEST-EQ", "Test Co", Segment.EQUITY, 1, BigDecimal("0.05"))
    private fun q(p: String) = MarketQuote("NSE:TEST-EQ", BigDecimal(p), bid = BigDecimal(p), ask = BigDecimal(p), timestamp = Instant.now(), source = QuoteSource.TEST)

    @Before fun setUp() = runBlocking { open() }
    private suspend fun open() { db = AppDatabase.build(ctx, name); repo = TradingRepository(db, { AppSettings(chargesEnabled = false) }); repo.init() }
    @After fun tearDown() { db.close(); ctx.deleteDatabase(name) }

    @Test fun startsWithTenLakh() = runBlocking {
        assertEquals(0, BigDecimal("1354510").compareTo(repo.account.first().wallet.cash))
    }

    @Test fun fillUpdatesWalletPositionLedgerAtomicallyAndSurvivesRestart() = runBlocking {
        val r = repo.place(info, OrderRequest(info.symbol, Side.BUY, OrderType.MARKET, 10), q("100"))
        assertEquals(OrderStatus.FILLED, r.order!!.status)
        db.close(); open() // simulate process restart
        val s = repo.account.first()
        assertEquals(0, BigDecimal("999000").compareTo(s.wallet.cash)); assertEquals(10, s.positions[info.symbol]!!.quantity); assertEquals(1, s.fills.size)
        assertEquals(0, s.wallet.cash.compareTo(s.ledger.fold(BigDecimal.ZERO) { a, e -> a + e.amount }))
    }

    @Test fun cachedQuoteCannotFillMarketOrder() = runBlocking {
        val r = repo.place(info, OrderRequest(info.symbol, Side.BUY, OrderType.MARKET, 1), q("100").copy(source = QuoteSource.CACHED))
        assertEquals(OrderStatus.REJECTED, r.order!!.status); assertTrue(repo.account.first().fills.isEmpty())
    }

    @Test fun exportThenImportRoundTrips() = runBlocking {
        repo.place(info, OrderRequest(info.symbol, Side.BUY, OrderType.MARKET, 3), q("50"))
        val json = repo.exportJson(); repo.resetSimulation(); assertTrue(repo.account.first().fills.isEmpty())
        repo.importJson(json); assertEquals(1, repo.account.first().fills.size)
    }

    @Test fun tamperedImportIsRejectedWithoutChangingData() = runBlocking {
        repo.place(info, OrderRequest(info.symbol, Side.BUY, OrderType.MARKET, 3), q("50"))
        val bad = repo.exportJson().replace("\"cash\": \"999850.00\"", "\"cash\": \"1.00\"").replace("\"cash\": \"999850\"", "\"cash\": \"1\"")
        try { repo.importJson(bad); fail("should reject") } catch (_: IllegalArgumentException) {}
        assertEquals(1, repo.account.first().fills.size)
    }
}
