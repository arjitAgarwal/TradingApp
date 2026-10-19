package com.papertrade.app

import com.papertrade.app.data.market.*
import com.papertrade.core.OptionType
import com.papertrade.core.Segment
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.zip.GZIPOutputStream

/** Upstox adapter against a fake Upstox: rows below are copied from Upstox's real NSE/BSE instrument files (Oct 2026). */
class UpstoxProviderTest {
    private val now = Instant.parse("2026-10-16T05:00:00Z") // a Friday, 10:30 IST

    private val nse = """[
      {"segment":"NSE_EQ","name":"RELIANCE INDUSTRIES LTD","exchange":"NSE","isin":"INE002A01018","instrument_type":"EQ","instrument_key":"NSE_EQ|INE002A01018","lot_size":1,"freeze_quantity":100000.0,"exchange_token":"2885","tick_size":10.0,"trading_symbol":"RELIANCE","short_name":"Reliance Industries","qty_multiplier":1.0,"security_type":"NORMAL","cas_eligible":true},
      {"segment":"NSE_EQ","name":"SDL RJ 7.49% 2035","exchange":"NSE","isin":"IN2920250163","instrument_type":"SG","instrument_key":"NSE_EQ|IN2920250163","lot_size":100,"tick_size":1.0,"trading_symbol":"749RJ35"},
      {"segment":"NSE_INDEX","name":"Nifty 50","exchange":"NSE","instrument_type":"INDEX","instrument_key":"NSE_INDEX|Nifty 50","exchange_token":"26000","trading_symbol":"NIFTY"},
      {"weekly":true,"segment":"NSE_FO","name":"NIFTY","exchange":"NSE","expiry":1792434599000,"instrument_type":"CE","underlying_symbol":"NIFTY","instrument_key":"NSE_FO|47129","lot_size":65,"tick_size":5.0,"trading_symbol":"NIFTY 22400 CE 19 OCT 26","strike_price":22400.0},
      {"weekly":true,"segment":"NSE_FO","name":"NIFTY","exchange":"NSE","expiry":1792434599000,"instrument_type":"PE","underlying_symbol":"NIFTY","instrument_key":"NSE_FO|47130","lot_size":65,"tick_size":5.0,"trading_symbol":"NIFTY 22400 PE 19 OCT 26","strike_price":22400.0},
      {"weekly":false,"segment":"NSE_FO","name":"NIFTY","exchange":"NSE","expiry":1793125799000,"instrument_type":"CE","underlying_symbol":"NIFTY","instrument_key":"NSE_FO|51326","lot_size":65,"tick_size":5.0,"trading_symbol":"NIFTY 22400 CE 27 OCT 26","strike_price":22400.0},
      {"weekly":false,"segment":"NSE_FO","name":"NIFTY","exchange":"NSE","expiry":1793125799000,"instrument_type":"FUT","underlying_symbol":"NIFTY","instrument_key":"NSE_FO|50001","lot_size":65,"tick_size":10.0,"trading_symbol":"NIFTY FUT 27 OCT 26","strike_price":0.0},
      {"weekly":false,"segment":"NSE_FO","name":"BANKNIFTY","exchange":"NSE","expiry":1793125799000,"instrument_type":"CE","underlying_symbol":"BANKNIFTY","instrument_key":"NSE_FO|60000","lot_size":30,"tick_size":5.0,"trading_symbol":"BANKNIFTY 50000 CE 27 OCT 26","strike_price":50000.0}
    ]"""

    private class Fake(val nse: String) : Interceptor {
        val paths = mutableListOf<String>()
        var quotesBody = "{}"; var chainBody = "{}"; var status = 200
        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url; paths += url.encodedPath
            val (code, body) = when {
                url.host == "assets.upstox.com" -> 200 to (if (url.encodedPath.endsWith("NSE.json.gz")) gz(nse) else gz("[]"))
                url.encodedPath.endsWith("/market-quote/quotes") -> status to quotesBody.toByteArray()
                url.encodedPath.endsWith("/option/chain") -> status to chainBody.toByteArray()
                else -> 404 to "{}".toByteArray()
            }
            return Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x").body(body.toResponseBody(null)).build()
        }
        private fun gz(s: String) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(s.toByteArray()) } }.toByteArray()
    }

    private var rejected = 0
    private val creds = object : UpstoxCredentials { override suspend fun accessToken() = "TOKEN"; override suspend fun onTokenRejected(): Boolean { rejected++; return false } }
    private fun provider(fake: Fake) = UpstoxProvider(creds, File("build/tmp/upstox-${System.nanoTime()}"), OkHttpClient.Builder().addInterceptor(fake).build(), clock = { now })

    @Test fun symbolsMatchFyersFormatSoSavedWatchlistsKeepWorking() {
        val d19 = LocalDate.of(2026, 10, 19); val d27 = LocalDate.of(2026, 10, 27)
        assertEquals("NSE:NIFTY26O1922400CE", UpstoxProvider.appSymbol("NSE", "NIFTY", d19, true, OptionType.CE, BigDecimal("22400.0")))
        assertEquals("NSE:NIFTY26OCT22400CE", UpstoxProvider.appSymbol("NSE", "NIFTY", d27, false, OptionType.CE, BigDecimal("22400")))
        assertEquals("NSE:NIFTY26OCTFUT", UpstoxProvider.appSymbol("NSE", "NIFTY", d27, false, null, null))
        assertEquals("BSE:SENSEX26N1275400PE", UpstoxProvider.appSymbol("BSE", "SENSEX", LocalDate.of(2026, 11, 12), true, OptionType.PE, BigDecimal("75400")))
        assertEquals("NSE:NIFTY273055000CE",UpstoxProvider.appSymbol("NSE", "NIFTY", LocalDate.of(2027, 3, 5), true, OptionType.CE, BigDecimal("5000")))
    }

    @Test fun quotesMapUpstoxKeysBackToAppSymbols() = runBlocking {
        val fake = Fake(nse).apply {
            quotesBody = """{"status":"success","data":{
              "NSE_EQ:RELIANCE":{"ohlc":{"open":1400,"high":1410,"low":1390,"close":1405},"depth":{"buy":[{"quantity":5,"price":1404.9,"orders":1}],"sell":[{"quantity":3,"price":1405.1,"orders":1}]},
                "instrument_token":"NSE_EQ|INE002A01018","symbol":"RELIANCE","last_price":1405,"volume":1000,"oi":0,"net_change":5},
              "NSE_INDEX:Nifty 50":{"ohlc":{"open":24800,"high":24900,"low":24700,"close":24850},"instrument_token":"NSE_INDEX|Nifty 50","last_price":24850,"net_change":-50},
              "NSE_FO:NIFTY26O1922400CE":{"instrument_token":"NSE_FO|47129","last_price":488.3,"net_change":-220.2,"oi":12345}}}"""
        }
        val p = provider(fake)
        val q = p.quotes(listOf("NSE:RELIANCE-EQ", "NSE:NIFTY50-INDEX", "NSE:NIFTY26O1922400CE", "NSE:UNKNOWN-EQ")).getOrThrow().associateBy { it.symbol }
        assertEquals(3, q.size)
        val rel = q.getValue("NSE:RELIANCE-EQ")
        assertEquals(BigDecimal("1400"), rel.prevClose); assertEquals(BigDecimal("0.36"), rel.changePct)
        assertEquals(BigDecimal("1404.9"), rel.bid); assertEquals(BigDecimal("1405.1"), rel.ask); assertNull(rel.openInterest)
        assertEquals(BigDecimal("24900"), q.getValue("NSE:NIFTY50-INDEX").prevClose)
        assertEquals(12345L, q.getValue("NSE:NIFTY26O1922400CE").openInterest)
    }

    @Test fun instrumentListGivesLotAndTickInRupees() = runBlocking {
        val p = provider(Fake(nse))
        val rel = p.equityInfo("NSE:RELIANCE-EQ").getOrThrow()
        assertEquals(Segment.EQUITY, rel.segment); assertEquals(0, BigDecimal("0.1").compareTo(rel.tickSize)) // Upstox lists 10 paise
        assertTrue(p.equityInfo("NSE:749RJ35-EQ").isFailure) // govt securities aren't equities
        assertEquals(listOf("NSE:RELIANCE-EQ"), p.searchEquities("relia").getOrThrow().map { it.symbol })
        val fut = p.futures(Underlying.NIFTY).getOrThrow().single().info
        assertEquals("NSE:NIFTY26OCTFUT", fut.symbol); assertEquals(65, fut.lotSize); assertEquals(LocalDate.of(2026, 10, 27), fut.expiry)
    }

    @Test fun optionChainIsOneRequestAndOnlyTradableContracts() = runBlocking {
        val fake = Fake(nse).apply {
            chainBody = """{"status":"success","data":[{"expiry":"2026-10-19","strike_price":22400.0,"underlying_key":"NSE_INDEX|Nifty 50","underlying_spot_price":22650.5,
              "call_options":{"instrument_key":"NSE_FO|47129","market_data":{"ltp":488.3,"close_price":708.5,"volume":10,"oi":12345,"bid_price":488,"ask_price":489},"option_greeks":{"iv":14.2}},
              "put_options":{"instrument_key":"NSE_FO|99999","market_data":{"ltp":5.0,"close_price":4.0}}}]}"""
        }
        val c = provider(fake).optionChain(Underlying.NIFTY, null).getOrThrow()
        assertEquals(listOf(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 27)), c.expiries)
        assertEquals(LocalDate.of(2026, 10, 19), c.selectedExpiry); assertEquals(BigDecimal("22650.5"), c.underlyingLtp)
        val row = c.rows.single()
        assertEquals("NSE:NIFTY26O1922400CE", row.call!!.symbol); assertEquals(BigDecimal("-220.2"), row.call!!.quote.change)
        assertNull("a key missing from the instrument list has no verified lot size", row.put)
        assertEquals(1, fake.paths.count { it.endsWith("/option/chain") })
    }

    @Test fun expiredLoginAsksForANewOne() = runBlocking {
        val fake = Fake(nse).apply { status = 401; quotesBody = """{"status":"error","errors":[{"errorCode":"UDAPI100050","message":"Invalid token used to access API"}]}""" }
        val e = provider(fake).quotes(listOf("NSE:NIFTY50-INDEX")).exceptionOrNull() as ProviderException
        assertEquals(ProviderException.Kind.AUTH, e.kind); assertTrue(e.message!!.contains("3:30 AM")); assertEquals(1, rejected)
    }

    @Test fun rateLimitPausesRequests() = runBlocking {
        val fake = Fake(nse).apply { status = 429 }
        val p = provider(fake)
        assertEquals(ProviderException.Kind.RATE_LIMIT, (p.quotes(listOf("NSE:NIFTY50-INDEX")).exceptionOrNull() as ProviderException).kind)
        assertEquals(ProviderException.Kind.RATE_LIMIT, (p.quotes(listOf("NSE:NIFTY50-INDEX")).exceptionOrNull() as ProviderException).kind)
        assertEquals("second call is refused locally", 1, fake.paths.count { it.endsWith("/market-quote/quotes") })
    }
}
