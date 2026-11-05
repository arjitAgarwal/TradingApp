package com.papertrade.app

import com.papertrade.app.data.market.*
import com.papertrade.core.OptionType
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Option chains for F&O stocks (FYERS and demo); index chains are covered elsewhere. */
class StockOptionChainTest {
    private val fixed = Instant.parse("2026-10-16T05:00:00Z") // Friday 16 Oct 2026, 10:30 IST

    // FYERS symbol-master rows: name at 1, lot 3, tick 4, expiry epoch 8, ticker 9, underlying 13, strike 15, CE/PE 16.
    private fun masterRow(ticker: String, under: String, strike: Int, opt: String, lot: Int = 500) =
        listOf("1", "$under $strike $opt", "14", "$lot", "0.05", "", "", "", "1793125799", ticker, "", "", "", under, "", "$strike", opt).joinToString(",")
    private val nseFo = listOf(masterRow("NSE:RELIANCE26OCT2900CE", "RELIANCE", 2900, "CE"), masterRow("NSE:RELIANCE26OCT2900PE", "RELIANCE", 2900, "PE")).joinToString("\n")
    private val chainJson = """{"s":"ok","code":200,"data":{
        "expiryData":[{"date":"27-10-2026","expiry":"1793125799"},{"date":"24-11-2026","expiry":"1795544999"}],
        "optionsChain":[{"strike_price":-1,"symbol":"NSE:RELIANCE-EQ","ltp":2905.5},
          {"strike_price":2900,"option_type":"CE","symbol":"NSE:RELIANCE26OCT2900CE","ltp":61.2,"ltpch":4.1,"ltpchp":7.18},
          {"strike_price":2900,"option_type":"PE","symbol":"NSE:RELIANCE26OCT2900PE","ltp":52.0,"ltpch":-3.5,"ltpchp":-6.31}]}}"""

    private fun fyers(requests: MutableList<String>): FyersProvider {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.toString(); requests += url
            val body = if (url.endsWith("NSE_FO.csv")) nseFo else chainJson
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val creds = object : FyersCredentials { override suspend fun appId() = "APP"; override suspend fun accessToken() = "TOKEN" }
        // A fresh cache folder, so a symbol master saved by another test is never reused.
        return FyersProvider(creds, kotlin.io.path.createTempDirectory("fyers").toFile(), http, clock = { fixed }, dataBase = "https://fyers.test/data", masterBase = "https://fyers.test/sym")
    }

    @Test fun fyersStockChainUsesTheStockSymbolAndMasterMetadata() = runBlocking {
        val requests = mutableListOf<String>()
        val c = fyers(requests).stockOptionChain("NSE:RELIANCE-EQ", null, 5).getOrThrow()
        assertTrue(requests.any { it.contains("options-chain-v3") && it.contains("symbol=NSE%3ARELIANCE-EQ") })
        assertNull("a stock chain has no index", c.underlying)
        assertEquals(BigDecimal("2905.5"), c.underlyingLtp)
        assertEquals(LocalDate.of(2026, 10, 27), c.selectedExpiry)
        val row = c.rows.single(); val call = row.call!!
        assertEquals(500, call.info!!.lotSize); assertEquals("RELIANCE", call.info!!.underlying); assertEquals(OptionType.CE, call.info!!.optionType)
        assertEquals(BigDecimal("52.0"), row.put!!.quote.ltp)
    }

    @Test fun fyersStockWithoutOptionsFailsWithoutAChainRequest() = runBlocking {
        val requests = mutableListOf<String>()
        val e = fyers(requests).stockOptionChain("NSE:TATACAP-EQ", null, 5).exceptionOrNull() as ProviderException
        assertEquals(ProviderException.Kind.UNSUPPORTED, e.kind); assertTrue(e.message!!.contains("TATACAP"))
        assertTrue("only the symbol master was read", requests.none { it.contains("options-chain") })
    }

    private val demo = DemoProvider(clock = { fixed })

    @Test fun demoStockChainIsMonthlyAndTradable() = runBlocking {
        val c = demo.stockOptionChain("NSE:RELIANCE-EQ", null, 3).getOrThrow()
        assertNull(c.underlying)
        // Stock options expire on the last Tuesday of the month.
        assertEquals(listOf(LocalDate.of(2026, 10, 27), LocalDate.of(2026, 11, 24), LocalDate.of(2026, 12, 29)), c.expiries)
        assertEquals(7, c.rows.size)
        val call = c.rows[3].call!!
        assertEquals("RELIANCE", call.info!!.underlying); assertEquals(500, call.info!!.lotSize)
        // Its contracts price through the normal quote path, so they can be watched and traded.
        assertEquals(call.quote.ltp, demo.quotes(listOf(call.symbol)).getOrThrow().single().ltp)
    }

    @Test fun demoStockWithoutOptionsAndIndexChainsStillWork() = runBlocking {
        val e = demo.stockOptionChain("NSE:TATACAP-EQ", null, 3).exceptionOrNull() as ProviderException
        assertEquals(ProviderException.Kind.UNSUPPORTED, e.kind)
        val nifty = demo.optionChain(Underlying.NIFTY, null, 2).getOrThrow()
        assertEquals(Underlying.NIFTY, nifty.underlying); assertEquals(LocalDate.of(2026, 10, 20), nifty.selectedExpiry)
        assertNotNull(demo.quotes(listOf(nifty.rows[0].put!!.symbol)).getOrThrow().singleOrNull())
    }
}
