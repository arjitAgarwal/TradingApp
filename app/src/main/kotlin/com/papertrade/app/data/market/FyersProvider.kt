package com.papertrade.app.data.market

import com.papertrade.core.MarketHours
import com.papertrade.core.OptionType
import com.papertrade.core.QuoteSource
import com.papertrade.core.Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

interface FyersCredentials {
    suspend fun appId(): String?
    suspend fun accessToken(): String?
    /** Called when FYERS rejects a token; return true if a fresh one is now available (the request is retried once). */
    suspend fun onTokenRejected(): Boolean = false
}

/**
 * FYERS API v3 READ-ONLY market-data adapter. It calls NO order, funds, holdings or position endpoints.
 *
 * STATUS: written from FYERS' published docs and NOT yet exercised against a live account (needs credentials + market hours).
 * Verify every path/field against https://fyers.in/api-docs/v3/ before trusting it. Parsing is defensive: anything missing
 * becomes null / is skipped; nothing is invented.
 *
 * TERMS WARNING: FYERS' API Terms (s.11) state exchange data may not be used for paper/virtual trading or for building
 * charting tools without written consent. See docs/PROVIDERS.md. Using this adapter for this app is at the user's own risk.
 *
 * AUTH: [com.papertrade.app.data.repo.FyersAuth] supplies the app id and a daily access token, renewing it automatically
 * from a 15-day refresh token. A rejected token triggers one renewal + retry before the error surfaces.
 *
 * LIMITS (Standard plan): data REST 5/s, 50/min, 5000/day; exceeding the per-minute limit >3 times/day blocks the account for
 * the day. [SlidingWindowLimiter] keeps us under a conservative budget. WebSocket NOT implemented: quoteStream polls.
 */
class FyersProvider(
    private val credentials: FyersCredentials,
    private val cacheDir: File,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build(),
    private val clock: () -> Instant = { Instant.now() },
    private val pollIntervalMs: Long = 3_000,
    private val dataBase: String = "https://api-t1.fyers.in/data",
    private val masterBase: String = "https://public.fyers.in/sym_details",
) : MarketDataProvider {
    override val id = "fyers"
    override val displayName = "FYERS API v3 (polled REST)"
    override val mode = DataMode.LIVE_PROVIDER
    private val status = MutableStateFlow(FeedStatus(FeedState.NOT_CONFIGURED, "Log in to FYERS in Account → Market data"))
    override val feedStatus: StateFlow<FeedStatus> = status

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val limiter = SlidingWindowLimiter(maxPerSecond = 4, maxPerMinute = 40, clock = { System.currentTimeMillis() })
    private val dateFmt = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    private fun JsonElement?.str() = (this as? JsonPrimitive)?.content
    private fun JsonElement?.dec(): BigDecimal? = str()?.toBigDecimalOrNull()
    private fun JsonElement?.lng(): Long? = dec()?.toLong()

    private suspend fun getJson(url: String): JsonObject = try { getJsonOnce(url) } catch (e: ProviderException) {
        if (e.kind == ProviderException.Kind.AUTH && credentials.onTokenRejected()) getJsonOnce(url) else throw e
    }

    private suspend fun getJsonOnce(url: String): JsonObject = withContext(Dispatchers.IO) {
        val appId = credentials.appId(); val token = credentials.accessToken()
        if (appId.isNullOrBlank() || token.isNullOrBlank()) {
            status.value = FeedStatus(FeedState.NOT_CONFIGURED, "FYERS login needed (Account → Market data)")
            throw ProviderException("FYERS credentials not configured", ProviderException.Kind.NOT_CONFIGURED)
        }
        limiter.acquire()
        val req = Request.Builder().url(url).header("Authorization", "$appId:$token").build()
        try {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code == 429) throw ProviderException("Rate limited by FYERS", ProviderException.Kind.RATE_LIMIT)
                if (resp.code == 401 || resp.code == 403) {
                    val detail = runCatching { json.parseToJsonElement(body).jsonObject["message"].str() }.getOrNull() ?: body.take(200).ifBlank { "no message" }
                    throw ProviderException("FYERS rejected the login (HTTP ${resp.code}: $detail). Log in again from Account → Market data", ProviderException.Kind.AUTH)
                }
                val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse { throw ProviderException("Unparseable FYERS response (HTTP ${resp.code})") }
                if (obj["s"].str() == "error") {
                    val code = obj["code"].str()?.toIntOrNull()
                    val msg = obj["message"].str() ?: "FYERS error"
                    when (code) {
                        -8, -15, -16, -17 -> throw ProviderException("FYERS token invalid/expired: $msg", ProviderException.Kind.AUTH)
                        -429 -> throw ProviderException("Rate limited by FYERS: $msg", ProviderException.Kind.RATE_LIMIT)
                        else -> throw ProviderException("FYERS: $msg (code $code)", ProviderException.Kind.OTHER)
                    }
                }
                status.value = FeedStatus(FeedState.CONNECTED, "Polling every ${pollIntervalMs / 1000}s", clock())
                obj
            }
        } catch (e: IOException) {
            status.value = FeedStatus(FeedState.OFFLINE, "No connection: ${e.message}", status.value.lastSuccess)
            throw ProviderException("Network error: ${e.message}", ProviderException.Kind.NETWORK, e)
        } catch (e: ProviderException) {
            if (e.kind != ProviderException.Kind.NOT_CONFIGURED) status.value = FeedStatus(FeedState.ERROR, e.message, status.value.lastSuccess)
            throw e
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** Quotes received while the market is (likely) closed are tagged CACHED: shown with a timestamp, never fillable. */
    private fun sourceNow(now: Instant) = if (MarketHours.isLikelyOpen(now)) QuoteSource.LIVE else QuoteSource.CACHED

    override suspend fun quotes(symbols: List<String>): Result<List<MarketQuote>> = runCatching {
        val now = clock(); val src = sourceNow(now)
        symbols.distinct().chunked(50).flatMap { chunk ->
            val d = getJson("$dataBase/quotes?symbols=${enc(chunk.joinToString(","))}")["d"] as? JsonArray ?: JsonArray(emptyList())
            d.mapNotNull { el ->
                val o = el.jsonObject; if (o["s"].str() != "ok") return@mapNotNull null
                val v = o["v"] as? JsonObject ?: return@mapNotNull null
                val sym = o["n"].str() ?: v["symbol"].str() ?: return@mapNotNull null
                val ltp = v["lp"].dec() ?: return@mapNotNull null
                MarketQuote(sym, ltp, v["open_price"].dec(), v["high_price"].dec(), v["low_price"].dec(), v["prev_close_price"].dec(),
                    v["ch"].dec(), v["chp"].dec(), v["bid"].dec()?.takeIf { it.signum() > 0 }, v["ask"].dec()?.takeIf { it.signum() > 0 },
                    v["volume"].lng(), null, null, now, src)
            }
        }
    }

    // ---- symbol master (lot size / tick / expiry / strike come from here, never hardcoded) ----
    private class MasterRow(val ticker: String, val name: String, val lot: Int, val tick: BigDecimal, val expiry: LocalDate?, val underlying: String, val strike: BigDecimal?, val opt: OptionType?)
    private val masterMutex = Mutex()
    private val masterCache = HashMap<String, List<MasterRow>>()

    /**
     * Column layout assumed from FYERS' symbol-master docs: 0 fyToken, 1 description, 2 instrument type, 3 min lot size, 4 tick size,
     * 8 expiry (epoch s), 9 symbol ticker, 13 underlying symbol, 15 strike, 16 option type (CE/PE/XX). VERIFY against the
     * current docs; rows that fail validation are dropped (a contract without verified lot/tick is not tradable).
     */
    private suspend fun master(segment: String): List<MasterRow> = masterMutex.withLock {
        masterCache[segment]?.let { return it }
        val file = File(cacheDir, "$segment.csv")
        withContext(Dispatchers.IO) {
            if (!file.exists() || System.currentTimeMillis() - file.lastModified() > 12 * 3600_000L) {
                try {
                    http.newCall(Request.Builder().url("$masterBase/$segment.csv").build()).execute().use { r ->
                        if (!r.isSuccessful) throw ProviderException("Symbol master HTTP ${r.code}")
                        cacheDir.mkdirs(); file.writeText(r.body!!.string())
                    }
                } catch (e: IOException) { if (!file.exists()) throw ProviderException("Cannot download symbol master: ${e.message}", ProviderException.Kind.NETWORK, e) }
            }
        }
        val rows = withContext(Dispatchers.IO) {
            file.useLines { lines ->
                lines.mapNotNull { line ->
                    val c = line.split(',')
                    if (c.size < 17) return@mapNotNull null
                    val lot = c[3].trim().toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
                    val tick = c[4].trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return@mapNotNull null
                    val exp = c[8].trim().toLongOrNull()?.takeIf { it > 0 }?.let { LocalDate.ofInstant(Instant.ofEpochSecond(it), MarketHours.IST) }
                    val strike = c[15].trim().toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
                    val opt = when (c[16].trim()) { "CE" -> OptionType.CE; "PE" -> OptionType.PE; else -> null }
                    MasterRow(c[9].trim(), c[1].trim(), lot, tick, exp, c[13].trim(), strike, opt)
                }.toList()
            }
        }
        masterCache[segment] = rows; rows
    }

    private fun Underlying.masterSegment() = if (this == Underlying.NIFTY) "NSE_FO" else "BSE_FO"
    private fun MasterRow.toInfo(): InstrumentInfo {
        val seg = when { opt != null -> Segment.OPTION; ticker.endsWith("FUT") -> Segment.FUTURES; else -> Segment.EQUITY }
        return InstrumentInfo(ticker, name, seg, lot, tick, expiry, strike, opt, underlying)
    }

    override suspend fun searchEquities(query: String) = runCatching {
        if (query.length < 2) return@runCatching emptyList()
        master("NSE_CM").asSequence().filter { it.ticker.endsWith("-EQ") && (it.ticker.contains(query, true) || it.name.contains(query, true)) }
            .take(50).map { it.toInfo().copy(segment = Segment.EQUITY, lotSize = 1) }.toList()
    }

    override suspend fun equityInfo(symbol: String) = runCatching {
        master("NSE_CM").firstOrNull { it.ticker == symbol }?.toInfo()?.copy(segment = Segment.EQUITY, lotSize = 1)
            ?: throw ProviderException("No instrument metadata for $symbol", ProviderException.Kind.UNSUPPORTED)
    }

    override suspend fun futures(underlying: Underlying) = runCatching {
        val today = LocalDate.now(MarketHours.IST)
        val contracts = master(underlying.masterSegment()).filter { it.underlying == underlying.name && it.ticker.endsWith("FUT") && it.expiry != null && !it.expiry.isBefore(today) }
            .sortedBy { it.expiry }.take(3)
        if (contracts.isEmpty()) throw ProviderException("Provider lists no ${underlying.displayName} futures", ProviderException.Kind.UNSUPPORTED)
        val q = quotes(contracts.map { it.ticker }).getOrThrow().associateBy { it.symbol }
        contracts.map { FutureContract(it.toInfo(), q[it.ticker]) }
    }

    override suspend fun optionChain(underlying: Underlying, expiry: LocalDate?, strikesEachSide: Int) = runCatching {
        // Note: the chain's `timestamp` param selects an expiry by epoch; the nearest expiry is returned when omitted.
        var url = "$dataBase/options-chain-v3?symbol=${enc(underlying.indexSymbol)}&strikecount=${strikesEachSide.coerceIn(1, 50)}"
        if (expiry != null) {
            val first = getJson(url)
            val epoch = expiryEpoch(first, expiry)
            if (epoch != null) url += "&timestamp=$epoch"
        }
        val data = getJson(url)["data"] as? JsonObject ?: throw ProviderException("Option chain not available for ${underlying.displayName}", ProviderException.Kind.UNSUPPORTED)
        val expiries = (data["expiryData"] as? JsonArray).orEmpty().mapNotNull { runCatching { LocalDate.parse(it.jsonObject["date"].str(), dateFmt) }.getOrNull() }
        val chain = (data["optionsChain"] as? JsonArray).orEmpty().map { it.jsonObject }
        val now = clock(); val src = sourceNow(now)
        val under = chain.firstOrNull { it["strike_price"].dec()?.signum() == -1 || it["strike_price"].str() == "-1" }?.get("ltp").dec()
        val master = master(underlying.masterSegment()).associateBy { it.ticker }
        val rows = sortedMapOf<BigDecimal, Pair<OptionSide?, OptionSide?>>()
        for (e in chain) {
            val strike = e["strike_price"].dec()?.takeIf { it.signum() > 0 } ?: continue
            val type = when (e["option_type"].str()) { "CE" -> OptionType.CE; "PE" -> OptionType.PE; else -> continue }
            val sym = e["symbol"].str() ?: continue
            val ltp = e["ltp"].dec() ?: continue
            val iv = (e["greeks"] as? JsonObject)?.get("iv").dec() ?: e["iv"].dec()
            val q = MarketQuote(sym, ltp, change = e["ltpch"].dec(), changePct = e["ltpchp"].dec(), bid = e["bid"].dec()?.takeIf { it.signum() > 0 },
                ask = e["ask"].dec()?.takeIf { it.signum() > 0 }, volume = e["volume"].lng(), openInterest = e["oi"].lng(), impliedVol = iv, timestamp = now, source = src)
            val side = OptionSide(master[sym]?.toInfo(), sym, q)
            val cur = rows[strike] ?: (null to null)
            rows[strike] = if (type == OptionType.CE) side to cur.second else cur.first to side
        }
        val selected = expiry ?: expiries.firstOrNull() ?: throw ProviderException("Provider returned no expiries", ProviderException.Kind.UNSUPPORTED)
        OptionChain(underlying, under, expiries, selected, rows.map { OptionRow(it.key, it.value.first, it.value.second) }, now, src)
    }

    private fun expiryEpoch(data: JsonObject, target: LocalDate): String? =
        ((data["data"] as? JsonObject)?.get("expiryData") as? JsonArray)?.map { it.jsonObject }
            ?.firstOrNull { runCatching { LocalDate.parse(it["date"].str(), dateFmt) }.getOrNull() == target }?.get("expiry").str()

    override suspend fun candles(symbol: String, interval: CandleInterval) = runCatching {
        val to = clock().epochSecond; val from = to - interval.lookbackDays * 86400
        val url = "$dataBase/history?symbol=${enc(symbol)}&resolution=${interval.resolution}&date_format=0&range_from=$from&range_to=$to&cont_flag=1"
        val arr = getJson(url)["candles"] as? JsonArray ?: JsonArray(emptyList())
        arr.mapNotNull { row ->
            val r = row.jsonArray; if (r.size < 6) return@mapNotNull null
            Candle(Instant.ofEpochSecond(r[0].lng() ?: return@mapNotNull null), r[1].dec() ?: return@mapNotNull null, r[2].dec() ?: return@mapNotNull null,
                r[3].dec() ?: return@mapNotNull null, r[4].dec() ?: return@mapNotNull null, r[5].lng() ?: 0L)
        }
    }

    override fun quoteStream(symbols: Set<String>): Flow<MarketQuote> = flow {
        var backoff = pollIntervalMs
        while (true) {
            val r = quotes(symbols.toList())
            if (r.isSuccess) { r.getOrThrow().forEach { emit(it) }; backoff = pollIntervalMs }
            else {
                val k = (r.exceptionOrNull() as? ProviderException)?.kind
                if (k == ProviderException.Kind.NOT_CONFIGURED || k == ProviderException.Kind.AUTH) backoff = 30_000
                else backoff = (backoff * 2).coerceAtMost(30_000)
                status.value = FeedStatus(FeedState.RECONNECTING, r.exceptionOrNull()?.message, status.value.lastSuccess)
            }
            delay(backoff)
        }
    }
}

/** Keeps request rate under provider limits (blocks rather than risk an account block). */
class SlidingWindowLimiter(private val maxPerSecond: Int, private val maxPerMinute: Int, private val clock: () -> Long) {
    private val mutex = Mutex(); private val stamps = ArrayDeque<Long>()
    suspend fun acquire() = mutex.withLock {
        while (true) {
            val now = clock()
            while (stamps.isNotEmpty() && now - stamps.first() > 60_000) stamps.removeFirst()
            val inSecond = stamps.count { now - it <= 1000 }
            val wait = when {
                stamps.size >= maxPerMinute -> 60_000 - (now - stamps.first()) + 1
                inSecond >= maxPerSecond -> 1000 - (now - stamps.first { now - it <= 1000 }) + 1
                else -> 0L
            }
            if (wait <= 0) { stamps.addLast(now); return@withLock }
            delay(wait)
        }
    }
}
