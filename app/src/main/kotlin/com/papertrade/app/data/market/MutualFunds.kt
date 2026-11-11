package com.papertrade.app.data.market

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

data class MfScheme(val code: String, val name: String)
data class MfNav(val nav: BigDecimal, val date: LocalDate)

/**
 * READ-ONLY mutual fund NAVs from the free public mfapi.in (AMFI data, no login). AMFI publishes one NAV per fund per
 * business day, so values change once a day. Parsing is defensive: anything missing is skipped, nothing is invented.
 */
class MfApi(private val base: String = "https://api.mfapi.in", private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()) {
    suspend fun search(query: String): Result<List<MfScheme>> = get("$base/mf/search".toHttpUrl().newBuilder().addQueryParameter("q", query.trim()).build().toString()).mapCatching(::parseSearch)
    suspend fun latestNav(code: String): Result<MfNav> = get("$base/mf/$code/latest").mapCatching(::parseLatest)

    private suspend fun get(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).header("User-Agent", "PaperTrade").build()).execute().use { r ->
                if (!r.isSuccessful) throw ProviderException("Mutual fund NAV service error ${r.code}")
                r.body?.string() ?: ""
            }
        }.recoverCatching { e -> throw if (e is java.io.IOException) ProviderException("No connection to the mutual fund NAV service", ProviderException.Kind.NETWORK, e) else e }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val day = DateTimeFormatter.ofPattern("dd-MM-yyyy")
        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

        fun parseSearch(text: String): List<MfScheme> = json.parseToJsonElement(text).jsonArray.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            MfScheme(o.str("schemeCode") ?: return@mapNotNull null, o.str("schemeName") ?: return@mapNotNull null)
        }

        fun parseLatest(text: String): MfNav {
            val o = json.parseToJsonElement(text).jsonObject
            val d = o["data"]?.jsonArray?.firstOrNull()?.jsonObject ?: throw ProviderException("No NAV published for this fund")
            val nav = d.str("nav")?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: throw ProviderException("No NAV published for this fund")
            return MfNav(nav, LocalDate.parse(d.str("date") ?: throw ProviderException("NAV has no date"), day))
        }
    }
}
