package com.papertrade.app.data.repo

import com.papertrade.app.data.db.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant

data class Watchlist(val id: Long, val name: String, val items: List<WatchlistItemEntity>)

/** Local-only, multiple watchlists. */
class WatchlistRepository(db: AppDatabase) {
    private val dao = db.watchlists()
    val watchlists: Flow<List<Watchlist>> = combine(dao.lists(), dao.items()) { l, i -> l.map { w -> Watchlist(w.id, w.name, i.filter { it.watchlistId == w.id }) } }
    suspend fun ensureDefault() { if (dao.listCount() == 0) dao.insertList(WatchlistEntity(name = "My Watchlist")) }
    suspend fun create(name: String) = dao.insertList(WatchlistEntity(name = name.trim().ifBlank { "Watchlist" }))
    suspend fun rename(id: Long, name: String) = dao.rename(id, name.trim())
    suspend fun delete(id: Long) = dao.deleteListWithItems(id)
    suspend fun add(id: Long, symbol: String, name: String) = dao.addItem(WatchlistItemEntity(id, symbol, name, Instant.now().toEpochMilli()))
    suspend fun remove(id: Long, symbol: String) = dao.removeItem(id, symbol)
}
