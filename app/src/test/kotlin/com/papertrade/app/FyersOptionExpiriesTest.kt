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
import java.time.Instant
import java.time.LocalDate

class FyersOptionExpiriesTest {
    private val fixed = Instant.parse("2026-11-05T05:00:00Z")
    private val json = """{"s":"ok","code":200,"data":{"expiryData":[{"date":"09-11-2026","expiry":"1794220199"},{"date":"17-11-2026","expiry":"1794911399"}],"optionsChain":[]}}"""

    private fun fyers(requests: MutableList<String>): FyersProvider {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.toString(); requests += url
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val creds = object : FyersCredentials { override suspend fun appId() = "APP"; override suspend fun accessToken() = "TOKEN" }
        return FyersProvider(creds, kotlin.io.path.createTempDirectory("fyers").toFile(), http, clock = { fixed }, dataBase = "https://fyers.test/data", masterBase = "https://fyers.test/sym")
    }

    @Test fun readsTheDatesInOneRequestWithoutTheSymbolMaster() = runBlocking {
        val requests = mutableListOf<String>()
        assertEquals(listOf(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 17)), fyers(requests).optionExpiries(Underlying.NIFTY).getOrThrow())
        assertEquals(1, requests.size); assertTrue(requests.single().contains("options-chain-v3"))
    }
}
