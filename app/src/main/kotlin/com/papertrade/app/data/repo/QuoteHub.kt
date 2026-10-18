package com.papertrade.app.data.repo

import com.papertrade.app.data.db.MarketCacheDao
import com.papertrade.app.data.db.toCacheEntity
import com.papertrade.app.data.db.toCachedQuote
import com.papertrade.app.data.market.DataMode
import com.papertrade.app.data.market.MarketDataProvider
import com.papertrade.app.data.market.MarketQuote
import com.papertrade.app.data.market.Underlying
import com.papertrade.core.QuoteSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Single app-wide quote pipeline: provider stream -> latest-quote map (UI) + cache (offline) + paper engine (limit-order matching).
 * Screens call [subscribe]/[unsubscribe]; open orders and positions are always subscribed so they keep valuing/matching.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuoteHub(
    scope: CoroutineScope,
    private val providerFor: (DataMode) -> MarketDataProvider,
    private val settings: Flow<AppSettings>,
    private val repo: TradingRepository,
    private val cache: MarketCacheDao,
) {
    private val subs = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val _latest = MutableStateFlow<Map<String, MarketQuote>>(emptyMap())
    /** Latest quote per symbol. Offline/restart: cached quotes with their ORIGINAL timestamps and source=CACHED. */
    val latest: StateFlow<Map<String, MarketQuote>> = _latest
    private val _provider = MutableStateFlow(providerFor(AppSettings().dataMode))
    val provider: StateFlow<MarketDataProvider> = _provider

    /** Seeds a quote that arrived with a REST response (e.g. option chain) unless a newer one is already known. */
    fun seed(q: MarketQuote) = _latest.update { m -> val cur = m[q.symbol]; if (cur == null || cur.timestamp < q.timestamp) m + (q.symbol to q) else m }
    fun subscribe(owner: String, symbols: Set<String>) = subs.update { it + (owner to symbols) }
    fun unsubscribe(owner: String) = subs.update { it - owner }

    init {
        scope.launch {
            settings.map { it.dataMode }.distinctUntilChanged().collect { mode ->
                _provider.value = providerFor(mode)
                _latest.value = if (mode == DataMode.LIVE_PROVIDER) cache.quotesNow().associate { it.symbol to it.toCachedQuote() } else emptyMap()
            }
        }
        scope.launch {
            combine(_provider, subs, repo.activeSymbols) { p, s, a -> p to (s.values.flatten().toSet() + a + Underlying.values().map { it.indexSymbol }) }
                .distinctUntilChanged()
                .flatMapLatest { (p, symbols) -> p.quoteStream(symbols) }
                .collect { q ->
                    _latest.update { it + (q.symbol to q) }
                    if (q.source != QuoteSource.DEMO) runCatching { cache.upsertQuotes(listOf(q.toCacheEntity())) }
                    runCatching { repo.onQuote(q) }
                }
        }
    }
}
