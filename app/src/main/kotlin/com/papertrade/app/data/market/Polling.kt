package com.papertrade.app.data.market

import com.papertrade.core.MarketHours
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import java.time.Instant

/**
 * REST polling shared by the live providers. Spends as little of the provider's request quota as possible:
 * while the market is closed it reads one snapshot and then stops; on errors it backs off; while rate-limited it sends nothing.
 */
internal fun pollingQuoteStream(
    symbols: Set<String>,
    intervalMs: Long,
    clock: () -> Instant,
    status: MutableStateFlow<FeedStatus>,
    fetch: suspend (List<String>) -> Result<List<MarketQuote>>,
): Flow<MarketQuote> = flow {
    var backoff = intervalMs
    var haveCloseSnapshot = false
    while (true) {
        // Market closed: one snapshot of the last prices is enough; don't spend the quota re-reading them.
        if (!MarketHours.isLikelyOpen(clock())) {
            if (haveCloseSnapshot) { delay(60_000); continue }
        } else haveCloseSnapshot = false
        val r = fetch(symbols.toList())
        if (r.isSuccess) { r.getOrThrow().forEach { emit(it) }; backoff = intervalMs; haveCloseSnapshot = !MarketHours.isLikelyOpen(clock()) }
        else {
            val k = (r.exceptionOrNull() as? ProviderException)?.kind
            backoff = when (k) {
                ProviderException.Kind.NOT_CONFIGURED, ProviderException.Kind.AUTH -> 30_000
                ProviderException.Kind.RATE_LIMIT -> 60_000 // nothing is sent while blocked; this only re-checks the block
                else -> (backoff * 2).coerceAtMost(30_000)
            }
            status.value = FeedStatus(if (k == ProviderException.Kind.RATE_LIMIT) FeedState.ERROR else FeedState.RECONNECTING, r.exceptionOrNull()?.message, status.value.lastSuccess)
        }
        delay(backoff)
    }
}

/**
 * Live prices from a provider's WebSocket while the market is open, with REST as the safety net. [session] opens one
 * socket for [symbols] and emits quotes until it closes or fails; REST polling then covers a retry window (15 s, doubling
 * per failure up to 5 min) before the socket is tried again. While the market is closed there is nothing to stream, so one
 * REST snapshot a minute is enough.
 */
internal fun streamingQuoteStream(
    symbols: Set<String>,
    pollIntervalMs: () -> Long,
    clock: () -> Instant,
    status: MutableStateFlow<FeedStatus>,
    fetch: suspend (List<String>) -> Result<List<MarketQuote>>,
    session: (Set<String>) -> Flow<MarketQuote>,
): Flow<MarketQuote> = flow {
    if (symbols.isEmpty()) return@flow
    var retryMs = STREAM_RETRY_MS
    while (true) {
        if (!MarketHours.isLikelyOpen(clock())) {
            fetch(symbols.toList()).onSuccess { it.forEach { q -> emit(q) } }
            delay(60_000); continue
        }
        try {
            session(symbols).collect { emit(it); retryMs = STREAM_RETRY_MS }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
            val k = (e as? ProviderException)?.kind
            status.value = FeedStatus(if (k == ProviderException.Kind.RATE_LIMIT) FeedState.ERROR else FeedState.RECONNECTING,
                "Live stream interrupted (${e.message}); using regular updates for now", status.value.lastSuccess)
            if (k == ProviderException.Kind.NOT_CONFIGURED || k == ProviderException.Kind.AUTH) { delay(30_000); continue }
        }
        // Socket closed or failed: poll REST until the next attempt, backing off so a broken socket isn't hammered.
        val until = clock().plusMillis(retryMs)
        while (clock() < until) { fetch(symbols.toList()).onSuccess { it.forEach { q -> emit(q) } }; delay(pollIntervalMs()) }
        retryMs = (retryMs * 2).coerceAtMost(5 * 60_000L)
    }
}

private const val STREAM_RETRY_MS = 15_000L

/** TEMP rate-limit debugging: one logcat line per provider request (endpoint, status, running counts). Never logs auth headers. */
internal class HttpDebugLog(private val tag: String) {
    private val sentAt = java.util.concurrent.ConcurrentLinkedDeque<Long>(); private val sentTotal = java.util.concurrent.atomic.AtomicInteger()
    fun log(endpoint: String, code: Int, body: String, isError: Boolean) {
        val now = System.currentTimeMillis(); sentAt.addLast(now); while ((sentAt.peekFirst() ?: now) < now - 60_000) sentAt.pollFirst()
        val msg = "#${sentTotal.incrementAndGet()} last60s=${sentAt.size} $endpoint -> HTTP $code" + if (isError) " body=${body.take(300)}" else ""
        runCatching { if (isError) android.util.Log.w(tag, msg) else android.util.Log.d(tag, msg) }
    }
}
