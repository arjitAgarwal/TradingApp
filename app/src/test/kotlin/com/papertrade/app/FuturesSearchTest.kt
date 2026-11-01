package com.papertrade.app

import com.papertrade.app.data.market.*
import com.papertrade.core.Segment
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Searching futures by underlying, for the F&O filter of the header search (FYERS and demo). */
class FuturesSearchTest {
    private val fixed = Instant.parse("2026-10-16T05:00:00Z") // Friday 16 Oct 2026, 10:30 IST

    // FYERS symbol-master rows: name at 1, lot 3, tick 4, expiry epoch 8, ticker 9, underlying 13, strike 15, CE/PE/XX 16.
    private fun row(ticker: String, under: String, expiryEpoch: Long, opt: String = "XX", strike: String = "-1", lot: Int = 500) =
        listOf("1", "$under $ticker", "11", "$lot", "0.05", "", "", "", "$expiryEpoch", ticker, "", "", "", under, "", strike, opt).joinToString(",")
    private val oct27 = 1793125799L; private val nov24 = 1795544999L; private val sep29 = 1790690000L // 29 Sep 2026: expired
    private val nseFo = listOf(
        row("NSE:RELIANCE26NOVFUT", "RELIANCE", nov24), row("NSE:RELIANCE26OCTFUT", "RELIANCE", oct27), row("NSE:RELIANCE26SEPFUT", "RELIANCE", sep29),
        row("NSE:RELIANCE26OCT2900CE", "RELIANCE", oct27, "CE", "2900"), row("NSE:RELINFRA26OCTFUT", "RELINFRA", oct27),
        row("NSE:NIFTY26OCTFUT", "NIFTY", oct27, lot = 75),
    ).joinToString("\n")

    private fun fyers(): FyersProvider {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.toString()
            val (code, body) = if (url.endsWith("NSE_FO.csv")) 200 to nseFo else 404 to ""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
                .body(body.toResponseBody("text/csv".toMediaType())).build()
        }.build()
        val creds = object : FyersCredentials { override suspend fun appId() = "APP"; override suspend fun accessToken() = "TOKEN" }
        return FyersProvider(creds, kotlin.io.path.createTempDirectory("fyers").toFile(), http, clock = { fixed }, dataBase = "https://fyers.test/data", masterBase = "https://fyers.test/sym")
    }

    @Test fun fyersListsUnexpiredFuturesExactMatchFirst() = runBlocking {
        val r = fyers().searchFutures("reliance").getOrThrow()
        // Options and the expired September contract are left out; the BSE master failing doesn't break the search.
        assertEquals(listOf("NSE:RELIANCE26OCTFUT", "NSE:RELIANCE26NOVFUT"), r.map { it.symbol })
        assertTrue(r.all { it.segment == Segment.FUTURES && it.underlying == "RELIANCE" && it.lotSize == 500 })
        assertEquals(listOf("NSE:RELIANCE26OCTFUT", "NSE:RELIANCE26NOVFUT", "NSE:RELINFRA26OCTFUT"), fyers().searchFutures("rel").getOrThrow().map { it.symbol })
        assertTrue(fyers().searchFutures("r").getOrThrow().isEmpty())
    }

    private val demo = DemoProvider(clock = { fixed })

    @Test fun demoStockFuturesAreMonthlyAndPriced() = runBlocking {
        val r = demo.searchFutures("rel").getOrThrow()
        assertEquals(listOf(LocalDate.of(2026, 10, 27), LocalDate.of(2026, 11, 24), LocalDate.of(2026, 12, 29)), r.map { it.expiry })
        assertTrue(r.all { it.segment == Segment.FUTURES && it.underlying == "RELIANCE" && it.lotSize == 500 })
        // They price through the normal quote path, so they can be watched and traded.
        assertNotNull(demo.quotes(listOf(r[0].symbol)).getOrThrow().singleOrNull())
        // Matching by company name works too, and the index futures are the ones the Futures page lists.
        assertEquals("TCS", demo.searchFutures("Tata Consultancy").getOrThrow().first().underlying)
        assertEquals(demo.futures(Underlying.NIFTY).getOrThrow().map { it.info.symbol }, demo.searchFutures("nifty").getOrThrow().map { it.symbol })
    }
}
