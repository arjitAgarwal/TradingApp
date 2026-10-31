package com.papertrade.app

import com.papertrade.app.data.market.*
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

/** Five-level market depth for the quote sheet (FYERS /depth and demo). */
class MarketDepthTest {
    private val fixed = Instant.parse("2026-10-16T05:00:00Z")

    @Test fun fyersDepthReadsFiveLevelsTotalsAndOhlc() = runBlocking {
        val sym = "NSE:NIFTY26N0323350PE"
        val body = """{"s":"ok","code":200,"d":{"$sym":{"totalbuyqty":10075,"totalsellqty":21710,
            "bids":[{"price":342.1,"volume":520,"ord":3},{"price":342.05,"volume":195,"ord":1},{"price":339.6,"volume":65,"ord":1},{"price":338.65,"volume":325,"ord":2},{"price":338.45,"volume":65,"ord":1}],
            "ask":[{"price":346.35,"volume":260,"ord":1},{"price":346.45,"volume":260,"ord":1},{"price":346.55,"volume":260,"ord":1},{"price":346.65,"volume":260,"ord":1},{"price":346.75,"volume":260,"ord":1}],
            "o":420.45,"h":476.35,"l":277.0,"c":395.75,"ltp":339.85}}}"""
        val urls = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            urls += chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val creds = object : FyersCredentials { override suspend fun appId() = "APP"; override suspend fun accessToken() = "TOKEN" }
        val d = FyersProvider(creds, kotlin.io.path.createTempDirectory("fyers").toFile(), http, clock = { fixed }, dataBase = "https://fyers.test/data")
            .depth(sym).getOrThrow()
        assertTrue(urls.single().contains("/depth?symbol="))
        assertEquals(5, d.bids.size); assertEquals(5, d.asks.size)
        assertEquals(BigDecimal("342.1"), d.bids[0].price); assertEquals(520L, d.bids[0].qty)
        assertEquals(BigDecimal("346.35"), d.asks[0].price); assertEquals(10075L, d.totalBuyQty); assertEquals(21710L, d.totalSellQty)
        assertEquals(BigDecimal("420.45"), d.open); assertEquals(BigDecimal("395.75"), d.prevClose)
    }

    @Test fun demoDepthIsFiveTicksEachSideInWholeLots() = runBlocking {
        val demo = DemoProvider(clock = { fixed })
        val call = demo.optionChain(Underlying.NIFTY, null, 1).getOrThrow().rows[1].call!!
        val d = demo.depth(call.symbol).getOrThrow()
        assertEquals(5, d.bids.size); assertEquals(5, d.asks.size)
        assertTrue("bids fall, asks rise", d.bids.zipWithNext().all { (a, b) -> a.price > b.price } && d.asks.zipWithNext().all { (a, b) -> a.price < b.price })
        assertTrue("best bid below best ask", d.bids[0].price < d.asks[0].price)
        val lot = call.info!!.lotSize.toLong()
        assertTrue((d.bids + d.asks).all { it.qty > 0 && it.qty % lot == 0L })
        assertEquals("same instant, same book", d.bids, demo.depth(call.symbol).getOrThrow().bids)
    }
}
