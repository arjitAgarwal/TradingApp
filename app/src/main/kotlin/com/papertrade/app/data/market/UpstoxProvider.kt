package com.papertrade.app.data.market

import com.papertrade.core.MarketHours
import com.papertrade.core.OptionType
import com.papertrade.core.QuoteSource
import com.papertrade.core.Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

interface UpstoxCredentials {
    suspend fun accessToken(): String?
    /** Called when Upstox rejects the token (401). Upstox has no refresh token, so this normally means "log in again". */
    suspend fun onTokenRejected(): Boolean = false
}

/**
 * Upstox API v2/v3 READ-ONLY market-data adapter. It calls NO order, funds, holdings or position endpoints.
 *
 * SYMBOLS: the rest of the app (watchlists, orders, positions) stores FYERS-style symbols such as "NSE:SBIN-EQ" or
 * "NSE:NIFTY26O2022400CE". This adapter translates them to Upstox instrument keys ("NSE_EQ|INE…", "NSE_FO|12345") using
 * Upstox's daily instrument file, so switching providers keeps every saved symbol working.
 *
 * LIMITS (Upstox standard APIs): 50/s, 500/min, 2,000 per 30 min. [SlidingWindowLimiter] keeps us at 60/min, i.e. under
 * 2,000 per 30 minutes. Upstox's access token ends at 03:30 IST every day and there is no refresh token.
 *
 * STATUS: written from Upstox's published docs and NOT yet exercised against a live account. Verify against
 * https://upstox.com/developer/api-documentation/ and Upstox's API terms before relying on it.
 */
class UpstoxProvider(
    private val credentials: UpstoxCredentials,
    private val cacheDir: File,
    private val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build(),
    private val clock: () -> Instant = { Instant.now() },
    private val pollIntervalMs: Long = 3_000,
    private val apiBase: String = "https://api.upstox.com",
    private val assetsBase: String = "https://assets.upstox.com/market-quote/instruments/exchange",
) : MarketDataProvider {
    override val id = "upstox"
    override val displayName = "Upstox API (polled REST)"
    override val mode = DataMode.LIVE_PROVIDER
    private val status = MutableStateFlow(FeedStatus(FeedState.NOT_CONFIGURED, "Log in to Upstox in Account → Market data"))
    override val feedStatus: StateFlow<FeedStatus> = status

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val limiter = SlidingWindowLimiter(maxPerSecond = 10, maxPerMinute = 60, clock = { System.currentTimeMillis() })
    private val debug = HttpDebugLog("UpstoxHttp")

    private fun JsonElement?.str() = (this as? JsonPrimitive)?.content?.takeIf { it != "null" }
    private fun JsonElement?.dec(): BigDecimal? = str()?.toBigDecimalOrNull()
    private fun JsonElement?.lng(): Long? = dec()?.toLong()
    private fun JsonElement?.obj() = this as? JsonObject

    // ---- HTTP ----

    @Volatile private var blockedUntil: Instant? = null

    private suspend fun getJson(path: String, query: Map<String, String> = emptyMap()): JsonObject = withContext(Dispatchers.IO) {
        val token = credentials.accessToken()
        if (token.isNullOrBlank()) {
            status.value = FeedStatus(FeedState.NOT_CONFIGURED, "Upstox login needed (Account → Market data)")
            throw ProviderException("Upstox login needed. It ends at 3:30 AM every day; log in again from Account → Market data", ProviderException.Kind.NOT_CONFIGURED)
        }
        blockedUntil?.let { if (clock() < it) throw ProviderException(RATE_MSG, ProviderException.Kind.RATE_LIMIT).also { e -> status.value = FeedStatus(FeedState.ERROR, e.message, status.value.lastSuccess) } }
        limiter.acquire()
        val url = "$apiBase$path".toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", "application/json").build()
        try {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                debug.log(path, resp.code, body, resp.code != 200)
                val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                val err = (obj?.get("errors") as? JsonArray)?.firstOrNull().obj()
                val msg = err?.get("message").str() ?: obj?.get("message").str() ?: "HTTP ${resp.code}"
                when {
                    resp.code == 429 -> { blockedUntil = clock().plusSeconds(60); throw ProviderException(RATE_MSG, ProviderException.Kind.RATE_LIMIT) }
                    resp.code == 401 -> {
                        credentials.onTokenRejected()
                        throw ProviderException("Upstox login expired ($msg). It ends at 3:30 AM every day; log in again from Account → Market data", ProviderException.Kind.AUTH)
                    }
                    obj == null -> throw ProviderException("Unparseable Upstox response (HTTP ${resp.code})")
                    resp.code != 200 || obj["status"].str() == "error" -> throw ProviderException("Upstox: $msg (${err?.get("errorCode").str() ?: "HTTP ${resp.code}"})")
                }
                status.value = FeedStatus(FeedState.CONNECTED, "Polling every ${pollIntervalMs / 1000}s", clock())
                obj!!
            }
        } catch (e: IOException) {
            status.value = FeedStatus(FeedState.OFFLINE, "No connection: ${e.message}", status.value.lastSuccess)
            throw ProviderException("Network error: ${e.message}", ProviderException.Kind.NETWORK, e)
        } catch (e: ProviderException) {
            if (e.kind != ProviderException.Kind.NOT_CONFIGURED) status.value = FeedStatus(FeedState.ERROR, e.message, status.value.lastSuccess)
            throw e
        }
    }

    /** Quotes received while the market is (likely) closed are tagged CACHED: shown with a timestamp, never fillable. */
    private fun sourceNow(now: Instant) = if (MarketHours.isLikelyOpen(now)) QuoteSource.LIVE else QuoteSource.CACHED

    // ---- instrument master: Upstox key <-> app symbol, lot size, tick, expiry ----

    @Serializable
    private class Row(
        val segment: String = "", val instrument_key: String = "", val trading_symbol: String? = null, val name: String? = null,
        val instrument_type: String? = null, val expiry: Long? = null, val strike_price: Double? = null, val lot_size: Int? = null,
        val tick_size: Double? = null, val underlying_symbol: String? = null, val weekly: Boolean? = null,
    )
    private class Master(val byKey: Map<String, InstrumentInfo>, val keyOf: Map<String, String>, val equities: List<InstrumentInfo>)
    private val masterMutex = Mutex()
    private var master: Master? = null

    private suspend fun master(): Master = masterMutex.withLock {
        master?.let { return it }
        val rows = withContext(Dispatchers.IO) { listOf("NSE", "BSE").flatMap { readMasterFile(it) } }
        val byKey = HashMap<String, InstrumentInfo>(); val keyOf = HashMap<String, String>()
        INDEX_KEYS.forEach { (u, k) -> keyOf[u.indexSymbol] = k }
        for (r in rows) r.toInfo()?.let { byKey[r.instrument_key] = it; keyOf[it.symbol] = r.instrument_key }
        Master(byKey, keyOf, byKey.values.filter { it.segment == Segment.EQUITY }.sortedBy { it.symbol }).also { master = it }
    }

    /** Downloads (at most every 12 h) and stream-parses one exchange file, keeping only rows this app can use. */
    @OptIn(ExperimentalSerializationApi::class)
    private fun readMasterFile(exchange: String): List<Row> {
        val file = File(cacheDir, "upstox_$exchange.json.gz")
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() > 12 * 3600_000L) {
            try {
                http.newCall(Request.Builder().url("$assetsBase/$exchange.json.gz").build()).execute().use { r ->
                    if (!r.isSuccessful) throw ProviderException("Upstox instrument list HTTP ${r.code}")
                    cacheDir.mkdirs(); val tmp = File(cacheDir, "${file.name}.part")
                    r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    tmp.renameTo(file)
                }
            } catch (e: IOException) { if (!file.exists()) throw ProviderException("Cannot download Upstox instrument list: ${e.message}", ProviderException.Kind.NETWORK, e) }
        }
        val wanted = Underlying.values().map { it.name }.toSet()
        return GZIPInputStream(file.inputStream().buffered()).use { input ->
            json.decodeToSequence(input, Row.serializer(), DecodeSequenceMode.ARRAY_WRAPPED).filter { r ->
                (r.segment == "NSE_EQ" && r.instrument_type == "EQ") ||
                    ((r.segment == "NSE_FO" || r.segment == "BSE_FO") && r.underlying_symbol in wanted && r.instrument_type in setOf("FUT", "CE", "PE"))
            }.toList()
        }
    }

    private fun Row.toInfo(): InstrumentInfo? {
        val lot = lot_size?.takeIf { it > 0 } ?: return null
        val tick = tick_size?.takeIf { it > 0 }?.let { BigDecimal.valueOf(it).movePointLeft(2).stripTrailingZeros() } ?: return null // Upstox ticks are in paise
        if (segment == "NSE_EQ") {
            val ts = trading_symbol ?: return null
            return InstrumentInfo("NSE:$ts-EQ", name ?: ts, Segment.EQUITY, 1, tick)
        }
        val exp = expiry?.let { Instant.ofEpochMilli(it).atZone(MarketHours.IST).toLocalDate() } ?: return null
        val und = underlying_symbol ?: return null
        val ex = segment.substringBefore('_')
        val opt = when (instrument_type) { "CE" -> OptionType.CE; "PE" -> OptionType.PE; else -> null }
        val strike = strike_price?.takeIf { it > 0 && opt != null }?.let { BigDecimal.valueOf(it).stripTrailingZeros() }
        val sym = appSymbol(ex, und, exp, weekly == true, opt, strike)
        val label = "$und ${NAME_DATE.format(exp)}" + (if (opt == null) " FUT" else " ${strike!!.toPlainString()} ${opt.name}")
        return InstrumentInfo(sym, label, if (opt == null) Segment.FUTURES else Segment.OPTION, lot, tick, exp, strike, opt, und)
    }

    // ---- MarketDataProvider ----

    override suspend fun quotes(symbols: List<String>): Result<List<MarketQuote>> = runCatching {
        val now = clock(); val src = sourceNow(now)
        val needsMaster = symbols.any { s -> INDEX_KEYS.none { it.first.indexSymbol == s } }
        val keyOf = if (needsMaster) master().keyOf else INDEX_KEYS.associate { it.first.indexSymbol to it.second }
        val symOf = symbols.distinct().mapNotNull { s -> keyOf[s]?.let { it to s } }.toMap()
        symOf.keys.chunked(500).flatMap { chunk ->
            val data = getJson("/v2/market-quote/quotes", mapOf("instrument_key" to chunk.joinToString(",")))["data"].obj() ?: return@flatMap emptyList()
            data.values.mapNotNull { el ->
                val v = el.obj() ?: return@mapNotNull null
                val sym = symOf[v["instrument_token"].str()] ?: return@mapNotNull null
                val ltp = v["last_price"].dec() ?: return@mapNotNull null
                val ch = v["net_change"].dec(); val prev = ch?.let { ltp - it }
                val ohlc = v["ohlc"].obj(); val depth = v["depth"].obj()
                fun best(side: String) = ((depth?.get(side) as? JsonArray)?.firstOrNull().obj())?.get("price").dec()?.takeIf { it.signum() > 0 }
                MarketQuote(sym, ltp, ohlc?.get("open").dec(), ohlc?.get("high").dec(), ohlc?.get("low").dec(), prev, ch, pct(ch, prev),
                    best("buy"), best("sell"), v["volume"].lng(), v["oi"].lng()?.takeIf { it > 0 }, null, now, src)
            }
        }
    }

    override suspend fun searchEquities(query: String) = runCatching {
        if (query.length < 2) return@runCatching emptyList()
        val q = query.trim().uppercase()
        val eq = master().equities
        (eq.filter { it.symbol.removePrefix("NSE:").startsWith(q) } + eq.filter { it.name.uppercase().contains(q) }).distinct().take(30)
    }

    override suspend fun equityInfo(symbol: String) = runCatching {
        master().keyOf[symbol]?.let { master().byKey[it] }?.takeIf { it.segment == Segment.EQUITY }
            ?: throw ProviderException("No instrument metadata for $symbol", ProviderException.Kind.UNSUPPORTED)
    }

    private suspend fun derivatives(u: Underlying) = master().byKey.values.filter { it.underlying == u.name && !it.expiry!!.isBefore(LocalDate.now(MarketHours.IST)) }

    override suspend fun futures(underlying: Underlying) = runCatching {
        val contracts = derivatives(underlying).filter { it.segment == Segment.FUTURES }.sortedBy { it.expiry }.take(3)
        if (contracts.isEmpty()) throw ProviderException("Upstox lists no ${underlying.displayName} futures", ProviderException.Kind.UNSUPPORTED)
        val q = quotes(contracts.map { it.symbol }).getOrThrow().associateBy { it.symbol }
        contracts.map { FutureContract(it, q[it.symbol]) }
    }

    override suspend fun optionChain(underlying: Underlying, expiry: LocalDate?, strikesEachSide: Int) = runCatching {
        val m = master()
        val expiries = derivatives(underlying).filter { it.segment == Segment.OPTION }.mapNotNull { it.expiry }.distinct().sorted()
        val selected = expiry ?: expiries.firstOrNull() ?: throw ProviderException("Upstox lists no ${underlying.displayName} option expiries", ProviderException.Kind.UNSUPPORTED)
        val data = getJson("/v2/option/chain", mapOf("instrument_key" to indexKey(underlying), "expiry_date" to selected.toString()))["data"] as? JsonArray
            ?: throw ProviderException("Option chain not available for ${underlying.displayName}", ProviderException.Kind.UNSUPPORTED)
        val now = clock(); val src = sourceNow(now)
        val entries = data.mapNotNull { it.obj() }
        val spot = entries.firstNotNullOfOrNull { it["underlying_spot_price"].dec() }
        fun side(o: JsonObject?): OptionSide? {
            if (o == null) return null
            val key = o["instrument_key"].str() ?: return null
            val md = o["market_data"].obj() ?: return null
            val ltp = md["ltp"].dec() ?: return null
            val info = m.byKey[key] ?: return null // no verified lot/tick: not tradable, so not shown
            val close = md["close_price"].dec()?.takeIf { it.signum() > 0 }; val ch = close?.let { ltp - it }
            val q = MarketQuote(info.symbol, ltp, change = ch, changePct = pct(ch, close), prevClose = close, bid = md["bid_price"].dec()?.takeIf { it.signum() > 0 },
                ask = md["ask_price"].dec()?.takeIf { it.signum() > 0 }, volume = md["volume"].lng(), openInterest = md["oi"].lng(),
                impliedVol = o["option_greeks"].obj()?.get("iv").dec(), timestamp = now, source = src)
            return OptionSide(info, info.symbol, q)
        }
        val rows = entries.mapNotNull { e -> e["strike_price"].dec()?.let { OptionRow(it.stripTrailingZeros(), side(e["call_options"].obj()), side(e["put_options"].obj())) } }
            .filter { it.call != null || it.put != null }.sortedBy { it.strike }
        // Upstox returns every strike; keep the same window around the money that FYERS' strikecount gives.
        val atm = spot?.let { s -> rows.indices.minByOrNull { (rows[it].strike - s).abs() } } ?: (rows.size / 2)
        val window = rows.subList((atm - strikesEachSide).coerceAtLeast(0), (atm + strikesEachSide + 1).coerceAtMost(rows.size))
        OptionChain(underlying, spot, expiries, selected, window, now, src)
    }

    override suspend fun candles(symbol: String, interval: CandleInterval) = runCatching {
        val key = (INDEX_KEYS.firstOrNull { it.first.indexSymbol == symbol }?.second ?: master().keyOf[symbol])
            ?: throw ProviderException("Upstox has no instrument for $symbol", ProviderException.Kind.UNSUPPORTED)
        val (unit, n) = when (interval) {
            CandleInterval.M1 -> "minutes" to 1; CandleInterval.M5 -> "minutes" to 5; CandleInterval.M15 -> "minutes" to 15
            CandleInterval.H1 -> "hours" to 1; CandleInterval.D1 -> "days" to 1
        }
        val today = LocalDate.now(MarketHours.IST); val k = encPath(key)
        // Historical excludes the current session; the intraday endpoint supplies today's candles.
        val past = getJson("/v3/historical-candle/$k/$unit/$n/$today/${today.minusDays(interval.lookbackDays)}")
        val todays = runCatching { getJson("/v3/historical-candle/intraday/$k/$unit/$n") }.getOrNull() // a holiday has none; the chart still shows history
        (parseCandles(past) + (todays?.let(::parseCandles) ?: emptyList())).associateBy { it.time }.values.sortedBy { it.time }
    }

    private fun parseCandles(o: JsonObject): List<Candle> = ((o["data"].obj())?.get("candles") as? JsonArray).orEmpty().mapNotNull { row ->
        val r = row.jsonArray; if (r.size < 6) return@mapNotNull null
        Candle(runCatching { OffsetDateTime.parse(r[0].str()).toInstant() }.getOrNull() ?: return@mapNotNull null,
            r[1].dec() ?: return@mapNotNull null, r[2].dec() ?: return@mapNotNull null, r[3].dec() ?: return@mapNotNull null,
            r[4].dec() ?: return@mapNotNull null, r[5].lng() ?: 0L)
    }

    override fun quoteStream(symbols: Set<String>): Flow<MarketQuote> = pollingQuoteStream(symbols, pollIntervalMs, clock, status, ::quotes)

    private fun pct(ch: BigDecimal?, base: BigDecimal?) = if (ch == null || base == null || base.signum() == 0) null else ch.multiply(BigDecimal(100)).divide(base, 2, RoundingMode.HALF_UP)
    private fun indexKey(u: Underlying) = INDEX_KEYS.first { it.first == u }.second
    private fun encPath(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        private const val RATE_MSG = "Upstox request limit hit. Pausing live data for 1 minute."
        private val INDEX_KEYS = listOf(Underlying.NIFTY to "NSE_INDEX|Nifty 50", Underlying.SENSEX to "BSE_INDEX|SENSEX")
        private val NAME_DATE = DateTimeFormatter.ofPattern("dd MMM yy", Locale.ENGLISH)
        private val MONTH = DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH)

        /**
         * The FYERS-style symbol the rest of the app uses: futures NSE:NIFTY26OCTFUT; monthly options NSE:NIFTY26OCT22400CE;
         * weekly options NSE:NIFTY26O2022400CE (month as 1-9/O/N/D, then the day).
         */
        internal fun appSymbol(exchange: String, underlying: String, expiry: LocalDate, weekly: Boolean, opt: OptionType?, strike: BigDecimal?): String {
            val yy = "%02d".format(expiry.year % 100); val mmm = MONTH.format(expiry).uppercase()
            if (opt == null) return "$exchange:$underlying$yy${mmm}FUT"
            val k = strike!!.stripTrailingZeros().toPlainString()
            if (!weekly) return "$exchange:$underlying$yy$mmm$k${opt.name}"
            val m = when (expiry.monthValue) { 10 -> "O"; 11 -> "N"; 12 -> "D"; else -> expiry.monthValue.toString() }
            return "$exchange:$underlying$yy$m${"%02d".format(expiry.dayOfMonth)}$k${opt.name}"
        }
    }
}
