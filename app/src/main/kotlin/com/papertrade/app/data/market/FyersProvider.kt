package com.papertrade.app.data.market

import com.papertrade.core.MarketHours
import com.papertrade.core.OptionType
import com.papertrade.core.QuoteSource
import com.papertrade.core.Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
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
 * LIMITS: see [prime]. Prices stream over FYERS' Data Socket ([FyersHsm]), which doesn't use the REST quota; REST polling
 * is only the fallback while the socket is down.
 */
class FyersProvider(
    private val credentials: FyersCredentials,
    private val cacheDir: File,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build(),
    private val clock: () -> Instant = { Instant.now() },
    private val dataBase: String = "https://api-t1.fyers.in/data",
    private val masterBase: String = "https://public.fyers.in/sym_details",
) : MarketDataProvider {
    override val id = "fyers"
    override val displayName = "FYERS API v3 (live stream)"
    override val mode = DataMode.LIVE_PROVIDER
    private val status = MutableStateFlow(FeedStatus(FeedState.NOT_CONFIGURED, "Log in to FYERS in Account → Market data"))
    override val feedStatus: StateFlow<FeedStatus> = status

    /**
     * Which FYERS plan the account is on (set from Account → FYERS). Data API limits differ a lot:
     *   Standard: 5/s, 50/min, 5,000/day; socket 100 symbols.   Prime: 10/s, 500/min, 5,00,000/day; socket 5,000 symbols.
     * Exceeding the per-minute limit more than 3 times a day blocks the account for the day, so each limiter stays well under.
     */
    @Volatile var prime: Boolean = false
    private val standardLimiter = SlidingWindowLimiter(maxPerSecond = 4, maxPerMinute = 40, clock = { System.currentTimeMillis() })
    private val primeLimiter = SlidingWindowLimiter(maxPerSecond = 8, maxPerMinute = 300, clock = { System.currentTimeMillis() })
    private val limiter get() = if (prime) primeLimiter else standardLimiter
    /** REST polling interval (fallback only while streaming works): 1 s fits Prime; Standard's 50/min needs 3 s. */
    private val pollIntervalMs get() = if (prime) 1_000L else 3_000L
    private val maxStreamSymbols get() = if (prime) 5_000 else 100

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
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
        // While FYERS has refused us for rate, every further call would only count against the quota again: fail locally.
        blocked?.let { (until, msg) -> if (clock() < until) throw ProviderException(msg, ProviderException.Kind.RATE_LIMIT).also { status.value = FeedStatus(FeedState.ERROR, msg, status.value.lastSuccess) } }
        limiter.acquire()
        val req = Request.Builder().url(url).header("Authorization", "$appId:$token").build()
        try {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                debugLog(url, resp.code, body)
                val errCode = runCatching { json.parseToJsonElement(body).jsonObject["code"].str()?.toIntOrNull() }.getOrNull()
                if (resp.code == 429 || errCode == -429 || errCode == -360) {
                    val fyersMsg = runCatching { json.parseToJsonElement(body).jsonObject["message"].str() }.getOrNull().orEmpty()
                    throw rateLimited(daily = errCode == -360 || fyersMsg.contains("/day"))
                }
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
                        else -> throw ProviderException("FYERS: $msg (code $code)", ProviderException.Kind.OTHER)
                    }
                }
                if (!status.value.message.orEmpty().startsWith("Streaming")) status.value = FeedStatus(FeedState.CONNECTED, "Polling every ${pollIntervalMs / 1000}s", clock())
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

    /** Set when FYERS refuses for rate: (retry-after time, user-facing reason). */
    @Volatile private var blocked: Pair<Instant, String>? = null
    private fun rateLimited(daily: Boolean): ProviderException {
        val now = clock()
        blocked = if (daily) now.atZone(MarketHours.IST).toLocalDate().plusDays(1).atStartOfDay(MarketHours.IST).toInstant() to
            (if (prime) "FYERS daily limit reached (5,00,000 requests/day on Prime)." else "FYERS daily limit reached (5,000 requests/day on the free Standard plan).") + " Live data resumes tomorrow."
        else now.plusSeconds(60) to "FYERS per-minute request limit hit. Pausing live data for 1 minute."
        return ProviderException(blocked!!.second, ProviderException.Kind.RATE_LIMIT)
    }

    private val debug = HttpDebugLog("FyersHttp")
    private fun debugLog(url: String, code: Int, body: String) =
        debug.log(url.removePrefix(dataBase).substringBefore('?'), code, body, code != 200 || body.contains("\"s\":\"error\""))

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
                    val exp = c[8].trim().toLongOrNull()?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).atZone(MarketHours.IST).toLocalDate() }
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
            // Epochs are remembered from earlier responses, so a refresh costs one request instead of two.
            val epoch = expiryEpochs[underlying]?.get(expiry) ?: run { val first = getJson(url); rememberEpochs(underlying, first["data"] as? JsonObject); expiryEpochs[underlying]?.get(expiry) }
            if (epoch != null) url += "&timestamp=$epoch"
        }
        val data = getJson(url)["data"] as? JsonObject ?: throw ProviderException("Option chain not available for ${underlying.displayName}", ProviderException.Kind.UNSUPPORTED)
        rememberEpochs(underlying, data)
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

    private val expiryEpochs = java.util.concurrent.ConcurrentHashMap<Underlying, Map<LocalDate, String>>()
    private fun rememberEpochs(u: Underlying, data: JsonObject?) {
        val m = (data?.get("expiryData") as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e.jsonObject; val d = runCatching { LocalDate.parse(o["date"].str(), dateFmt) }.getOrNull() ?: return@mapNotNull null
            o["expiry"].str()?.let { d to it }
        }.toMap()
        if (m.isNotEmpty()) expiryEpochs[u] = m
    }

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

    /**
     * Live prices over FYERS' Data Socket while the market is open; see [streamingQuoteStream] for the REST fallback.
     * The socket doesn't count against the REST quota, which is what makes the Standard plan's 5,000/day last a full session.
     */
    override fun quoteStream(symbols: Set<String>): Flow<MarketQuote> = streamingQuoteStream(symbols, { pollIntervalMs }, clock, status, ::quotes) { syms ->
        channelFlow {
            val token = credentials.accessToken() ?: throw ProviderException("FYERS login needed", ProviderException.Kind.NOT_CONFIGURED)
            val key = FyersHsm.hsmKey(token) ?: throw ProviderException("FYERS login has no live-feed key; log in again", ProviderException.Kind.AUTH)
            val symOf = topics(syms.toList()).entries.take(maxStreamSymbols).associate { (sym, topic) -> topic to sym }
            if (symOf.isEmpty()) return@channelFlow
            // Symbols the socket can't carry (over the plan's cap, or no token) are polled over REST so they never go stale.
            // Standard's 5,000/day can't afford 3 s polling all session, so these get 15 s there.
            val streamed = symOf.values.toSet(); val leftover = syms.filter { it !in streamed }
            val poller = if (leftover.isEmpty()) null else launch {
                while (true) { quotes(leftover).onSuccess { it.forEach { q -> send(q) } }; delay(if (prime) pollIntervalMs else 15_000L) }
            }
            val last = HashMap<String, MarketQuote>()
            try {
                socket(key, symOf.keys.toList()).collect { u ->
                    val sym = symOf[u.topic] ?: return@collect
                    val now = clock()
                    merge(last[sym], sym, u, now)?.let { q -> last[sym] = q; send(q) }
                }
            } finally { poller?.cancel() }
        }
    }

    /** One socket connection: auth, full mode, subscribe, then decoded updates until it closes. */
    private fun socket(hsmKey: String, topics: List<String>): Flow<FyersHsmDecoder.Update> = callbackFlow {
        val decoder = FyersHsmDecoder()
        val ws = streamHttp.newWebSocket(Request.Builder().url(FyersHsm.URL).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(FyersHsm.auth(hsmKey).toByteString())
                webSocket.send(FyersHsm.fullMode().toByteString())
                topics.chunked(1500).forEach { webSocket.send(FyersHsm.subscribe(it).toByteString()) }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                when (val e = runCatching { decoder.decode(bytes.toByteArray()) }.getOrElse { debug.log("ws decode", 0, it.toString(), true); return }) {
                    is FyersHsmDecoder.Event.Auth -> {
                        debug.log("ws auth (${topics.size} topics)", if (e.ok) 200 else 401, "", !e.ok)
                        if (!e.ok) close(ProviderException("FYERS refused the live-feed login; log in again", ProviderException.Kind.AUTH))
                    }
                    is FyersHsmDecoder.Event.Data -> {
                        e.ack?.let { webSocket.send(it.toByteString()) }
                        if (e.updates.isNotEmpty()) status.value = FeedStatus(FeedState.CONNECTED, "Streaming live", clock())
                        e.updates.forEach { trySend(it) }
                    }
                    is FyersHsmDecoder.Event.ServerMessage -> debug.log("ws message", e.code, e.message, true)
                    FyersHsmDecoder.Event.Other -> {}
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { close(IOException("FYERS socket failed: ${t.message ?: response?.code}", t)) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close() }
        })
        val pinger = launch { while (true) { delay(10_000); ws.send(FyersHsm.PING.toByteString()) } }
        awaitClose { pinger.cancel(); ws.close(1000, null) }
    }

    private fun merge(prev: MarketQuote?, symbol: String, u: FyersHsmDecoder.Update, now: Instant): MarketQuote? {
        fun d(v: Double?) = v?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP) }
        val ltp = d(u.ltp) ?: prev?.ltp ?: return null
        val close = d(u.prevClose)?.takeIf { it.signum() > 0 } ?: prev?.prevClose
        val ch = close?.let { ltp - it }
        val pct = if (ch != null && close.signum() != 0) ch.multiply(BigDecimal(100)).divide(close, 2, RoundingMode.HALF_UP) else null
        return MarketQuote(symbol, ltp, d(u.open) ?: prev?.open, d(u.high) ?: prev?.high, d(u.low) ?: prev?.low, close, ch, pct,
            d(u.bid) ?: prev?.bid, d(u.ask) ?: prev?.ask, u.volume ?: prev?.volume, u.oi ?: prev?.openInterest, null, now, sourceNow(now))
    }

    /** Symbol -> HSM topic. Looked up once per symbol via FYERS' symbol-token API (as the official SDK does) and remembered. */
    private val topicCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private suspend fun topics(symbols: List<String>): Map<String, String> {
        val missing = symbols.filter { !topicCache.containsKey(it) }
        missing.chunked(500).forEach { chunk -> symbolTokens(chunk).forEach { (sym, fyToken) -> FyersHsm.topic(sym, fyToken)?.let { topicCache[sym] = it } } }
        return symbols.mapNotNull { s -> topicCache[s]?.let { s to it } }.toMap()
    }

    private suspend fun symbolTokens(symbols: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        val token = credentials.accessToken() ?: throw ProviderException("FYERS login needed", ProviderException.Kind.NOT_CONFIGURED)
        blocked?.let { (until, msg) -> if (clock() < until) throw ProviderException(msg, ProviderException.Kind.RATE_LIMIT) }
        limiter.acquire()
        val body = """{"symbols":[${symbols.joinToString(",") { "\"$it\"" }}]}""".toRequestBody("application/json".toMediaType())
        // The SDK sends the bare token (no "appId:") to this endpoint.
        val req = Request.Builder().url("$dataBase/symbol-token").post(body).header("Authorization", token.substringAfter(':')).build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty(); debugLog("$dataBase/symbol-token", resp.code, text)
                if (resp.code == 429) throw rateLimited(daily = text.contains("/day"))
                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse { throw ProviderException("Unparseable FYERS symbol-token response (HTTP ${resp.code})") }
                if (obj["s"].str() != "ok") throw ProviderException("FYERS symbol-token: ${obj["message"].str() ?: "HTTP ${resp.code}"}",
                    if (resp.code == 401 || resp.code == 403) ProviderException.Kind.AUTH else ProviderException.Kind.OTHER)
                (obj["validSymbol"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> v.str()?.let { k to it } }.toMap()
            }
        } catch (e: IOException) { throw ProviderException("Network error: ${e.message}", ProviderException.Kind.NETWORK, e) }
    }

    /** Sockets stay open for hours: no read timeout; FYERS' own ping (every 10 s) keeps the connection alive. */
    private val streamHttp by lazy { http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build() }
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
