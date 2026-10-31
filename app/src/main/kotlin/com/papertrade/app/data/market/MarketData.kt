package com.papertrade.app.data.market

import com.papertrade.core.Instrument
import com.papertrade.core.OptionType
import com.papertrade.core.QuoteSource
import com.papertrade.core.Segment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Provider-independent market-data contracts. UI and the paper engine depend ONLY on these. */
enum class DataMode { LIVE_PROVIDER, DEMO }
/** Which broker supplies real prices when [DataMode.LIVE_PROVIDER] is on. */
enum class LiveSource(val displayName: String) { FYERS("FYERS"), UPSTOX("Upstox") }

enum class Underlying(val displayName: String, val indexSymbol: String) {
    NIFTY("NIFTY 50", "NSE:NIFTY50-INDEX"),
    SENSEX("SENSEX", "BSE:SENSEX-INDEX"),
}

data class MarketQuote(
    val symbol: String,
    val ltp: BigDecimal,
    val open: BigDecimal? = null,
    val high: BigDecimal? = null,
    val low: BigDecimal? = null,
    val prevClose: BigDecimal? = null,
    val change: BigDecimal? = null,
    val changePct: BigDecimal? = null,
    val bid: BigDecimal? = null,
    val ask: BigDecimal? = null,
    val volume: Long? = null,
    val openInterest: Long? = null,
    val impliedVol: BigDecimal? = null,
    /** When this app observed the quote (provider quote timestamps are not always available). */
    val timestamp: Instant,
    val source: QuoteSource,
)

data class InstrumentInfo(
    val symbol: String,
    val name: String,
    val segment: Segment,
    val lotSize: Int,
    val tickSize: BigDecimal,
    val expiry: LocalDate? = null,
    val strike: BigDecimal? = null,
    val optionType: OptionType? = null,
    val underlying: String? = null,
) {
    fun toCore() = Instrument(symbol, symbol, name, segment, lotSize, tickSize, expiry, strike, optionType, underlying)
}

data class OptionSide(val info: InstrumentInfo?, val symbol: String, val quote: MarketQuote)
data class OptionRow(val strike: BigDecimal, val call: OptionSide?, val put: OptionSide?)
data class OptionChain(
    /** The index, or null for a stock's chain. */
    val underlying: Underlying?,
    val underlyingLtp: BigDecimal?,
    val expiries: List<LocalDate>,
    val selectedExpiry: LocalDate,
    val rows: List<OptionRow>,
    val asOf: Instant,
    val source: QuoteSource,
)

/** One price level of the order book: price and the quantity (units, not lots) resting there. */
data class DepthLevel(val price: BigDecimal, val qty: Long)
/** Best five bids and asks with the day's open/high/low and previous close, for the quote sheet. */
data class MarketDepth(
    val symbol: String,
    val bids: List<DepthLevel>,
    val asks: List<DepthLevel>,
    val totalBuyQty: Long?,
    val totalSellQty: Long?,
    val open: BigDecimal?,
    val high: BigDecimal?,
    val low: BigDecimal?,
    val prevClose: BigDecimal?,
    val asOf: Instant,
)

data class FutureContract(val info: InstrumentInfo, val quote: MarketQuote?)
data class Candle(val time: Instant, val open: BigDecimal, val high: BigDecimal, val low: BigDecimal, val close: BigDecimal, val volume: Long)

enum class CandleInterval(val label: String, val resolution: String, val lookbackDays: Long) {
    M1("1m", "1", 1), M5("5m", "5", 5), M15("15m", "15", 10), H1("1h", "60", 30), D1("1D", "D", 365)
}

enum class FeedState { CONNECTED, RECONNECTING, OFFLINE, ERROR, NOT_CONFIGURED }
data class FeedStatus(val state: FeedState, val message: String? = null, val lastSuccess: Instant? = null)

class ProviderException(message: String, val kind: Kind = Kind.OTHER, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { NOT_CONFIGURED, AUTH, RATE_LIMIT, UNSUPPORTED, NETWORK, OTHER }
}

interface MarketDataProvider {
    val id: String
    val displayName: String
    val mode: DataMode
    val feedStatus: StateFlow<FeedStatus>

    suspend fun quotes(symbols: List<String>): Result<List<MarketQuote>>
    suspend fun searchEquities(query: String): Result<List<InstrumentInfo>>
    suspend fun equityInfo(symbol: String): Result<InstrumentInfo>
    suspend fun futures(underlying: Underlying): Result<List<FutureContract>>
    /** [expiry] null = nearest expiry the provider lists. */
    suspend fun optionChain(underlying: Underlying, expiry: LocalDate?, strikesEachSide: Int = 12): Result<OptionChain>
    /**
     * Option chain of an F&O stock, by its cash-market symbol ("NSE:RELIANCE-EQ"); [expiry] null = nearest. Fails with
     * [ProviderException.Kind.UNSUPPORTED] for a stock that has no options, or a provider that can't list them.
     */
    suspend fun stockOptionChain(symbol: String, expiry: LocalDate?, strikesEachSide: Int = 12): Result<OptionChain> =
        Result.failure(ProviderException("$displayName can't show stock option chains yet", ProviderException.Kind.UNSUPPORTED))
    /** Market depth (best five each side) for one symbol; [ProviderException.Kind.UNSUPPORTED] where a provider has none. */
    suspend fun depth(symbol: String): Result<MarketDepth> =
        Result.failure(ProviderException("$displayName can't show market depth yet", ProviderException.Kind.UNSUPPORTED))
    suspend fun candles(symbol: String, interval: CandleInterval): Result<List<Candle>>
    /**
     * Emits quotes for [symbols], which are in priority order (the set's iteration order). Implementations poll or stream;
     * one that can stream only some symbols streams the first ones. Callers must tolerate duplicates and gaps.
     */
    fun quoteStream(symbols: Set<String>): Flow<MarketQuote>
}
