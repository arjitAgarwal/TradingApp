package com.papertrade.app.ui

import android.content.Context
import com.papertrade.app.data.market.MarketDataProvider
import com.papertrade.app.data.market.ProviderException
import com.papertrade.app.data.market.Underlying
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/** Last dates read, per provider and index, so the chips show straight away on the next open. */
interface ExpiryCache {
    fun read(provider: String, u: Underlying): List<LocalDate>
    fun write(provider: String, u: Underlying, dates: List<LocalDate>)
}

class PrefsExpiryCache(context: Context) : ExpiryCache {
    private val prefs = context.getSharedPreferences("index_expiries", Context.MODE_PRIVATE)
    override fun read(provider: String, u: Underlying) = prefs.getString("$provider.${u.name}", null)?.split(',').orEmpty()
        .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
    override fun write(provider: String, u: Underlying, dates: List<LocalDate>) { prefs.edit().putString("$provider.${u.name}", dates.joinToString(",")).apply() }
}

/**
 * Option expiry dates per index, for the "Expiry Tue" chips. Each index loads on its own, once per provider per IST day.
 * A failed read retries with a growing wait while the app is on screen; one that needs a login waits for the login.
 * Changing provider, login, day or foreground state cancels reads in flight, so a late answer from the old provider is dropped.
 */
class IndexExpiries(
    private val provider: Flow<MarketDataProvider>,
    private val day: Flow<LocalDate>,
    private val login: Flow<*>,
    private val active: Flow<Boolean>,
    private val cache: ExpiryCache,
    private val log: (String) -> Unit = {},
    private val retrySeconds: List<Long> = listOf(5, 15, 30, 60),
) {
    private val _dates = MutableStateFlow<Map<Underlying, List<LocalDate>>>(emptyMap())
    val dates: StateFlow<Map<Underlying, List<LocalDate>>> = _dates
    private val loaded = ConcurrentHashMap<Underlying, LocalDate>() // index to the day its dates were read

    private class Request(val provider: MarketDataProvider, val day: LocalDate, val active: Boolean)

    suspend fun run() {
        var current: MarketDataProvider? = null
        combine(provider, day, login, active) { p, d, _, a -> Request(p, d, a) }.collectLatest { r ->
            if (r.provider !== current) {
                current = r.provider; loaded.clear()
                _dates.value = Underlying.values().associateWith { cache.read(r.provider.id, it) }.filterValues { it.isNotEmpty() }
            }
            if (r.active) coroutineScope { Underlying.values().filter { loaded[it] != r.day }.forEach { u -> launch { load(r.provider, u, r.day) } } }
        }
    }

    private suspend fun load(p: MarketDataProvider, u: Underlying, today: LocalDate) {
        var attempt = 0
        while (true) {
            val result = p.optionExpiries(u)
            currentCoroutineContext().ensureActive() // providers wrap calls in runCatching, which also catches cancellation
            val list = result.getOrNull().orEmpty()
            if (list.isNotEmpty()) { _dates.update { it + (u to list) }; cache.write(p.id, u, list); loaded[u] = today; return }
            val e = result.exceptionOrNull(); val kind = (e as? ProviderException)?.kind
            log("${p.id} ${u.name} expiries: attempt ${attempt + 1} failed: ${e?.message ?: "no dates listed"}")
            if (kind == ProviderException.Kind.NOT_CONFIGURED || kind == ProviderException.Kind.AUTH || kind == ProviderException.Kind.UNSUPPORTED) return
            val wait = retrySeconds[attempt.coerceAtMost(retrySeconds.lastIndex)]
            delay((if (kind == ProviderException.Kind.RATE_LIMIT) maxOf(wait, 60L) else wait) * 1000); attempt++
        }
    }
}
