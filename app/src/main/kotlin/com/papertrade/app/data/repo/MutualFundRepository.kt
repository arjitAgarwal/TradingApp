package com.papertrade.app.data.repo

import com.papertrade.app.data.db.AppDatabase
import com.papertrade.app.data.db.MutualFundEntity
import com.papertrade.app.data.market.MfApi
import com.papertrade.core.Dec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

/** One hand-entered fund. [nav] is the latest AMFI NAV, or the buy NAV until one has been fetched. */
data class MutualFundHolding(val schemeCode: String, val name: String, val units: BigDecimal, val avgNav: BigDecimal, val latestNav: BigDecimal?, val navDate: LocalDate?) {
    val nav get() = latestNav ?: avgNav
    val invested: BigDecimal get() = Dec.money(units * avgNav)
    val current: BigDecimal get() = Dec.money(units * nav)
    val pnl get() = current - invested
}

/**
 * Mutual funds the user brings in by hand (name, buy NAV, units). They sit beside the trading account: they are not traded,
 * use no virtual cash, and are valued at the latest AMFI NAV from [MfApi].
 */
class MutualFundRepository(db: AppDatabase, private val api: MfApi, private val clock: () -> Instant = { Instant.now() }) {
    private val dao = db.mutualFunds()
    private val mutex = Mutex()
    private var refreshedAt: Instant? = null

    val holdings: Flow<List<MutualFundHolding>> = dao.all().map { l -> l.map { it.toHolding() } }

    suspend fun search(q: String) = api.search(q)

    /** Adding a fund that is already held re-averages it (average cost), as a second purchase would. */
    suspend fun add(code: String, name: String, units: BigDecimal, price: BigDecimal) = mutex.withLock {
        require(units.signum() > 0 && price.signum() > 0) { "Units and NAV must be more than zero" }
        val old = dao.get(code)
        val f = if (old == null) MutualFundEntity(code, name, units.stripTrailingZeros().toPlainString(), Dec.price(price).toPlainString(), null, null, clock().toEpochMilli())
        else {
            val u0 = BigDecimal(old.units); val total = u0 + units
            old.copy(units = total.stripTrailingZeros().toPlainString(), avgNav = (u0 * BigDecimal(old.avgNav) + units * price).divide(total, Dec.PRICE_SCALE, RoundingMode.HALF_EVEN).toPlainString())
        }
        dao.upsert(f)
        refreshedAt = null
    }

    suspend fun remove(code: String) = dao.delete(code)
    suspend fun clear() = dao.clear()
    suspend fun exportAll() = dao.allNow()
    suspend fun replaceAll(f: List<MutualFundEntity>) = dao.replaceAll(f)

    /** Fetches the latest NAV of every fund, at most once every 30 minutes unless [force]d. Failures keep the old NAV. */
    suspend fun refreshNavs(force: Boolean = false): Result<Unit> = mutex.withLock {
        val last = refreshedAt
        if (!force && last != null && clock().isBefore(last.plusSeconds(1800))) return@withLock Result.success(Unit)
        val funds = dao.allNow(); var err: Throwable? = null
        funds.forEach { f -> api.latestNav(f.schemeCode).onSuccess { dao.setNav(f.schemeCode, it.nav.toPlainString(), it.date.toString()) }.onFailure { err = it } }
        if (err == null) refreshedAt = clock()
        err?.let { Result.failure(it) } ?: Result.success(Unit)
    }

    private fun MutualFundEntity.toHolding() = MutualFundHolding(schemeCode, name, BigDecimal(units), BigDecimal(avgNav), nav?.let(::BigDecimal), navDate?.let(LocalDate::parse))
}
