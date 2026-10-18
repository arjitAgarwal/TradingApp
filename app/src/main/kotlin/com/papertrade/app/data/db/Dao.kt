package com.papertrade.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TradingDao {
    @Query("SELECT * FROM wallet WHERE id = 1") fun walletFlow(): Flow<WalletEntity?>
    @Query("SELECT * FROM wallet WHERE id = 1") suspend fun wallet(): WalletEntity?
    @Query("SELECT * FROM orders ORDER BY createdAt DESC") fun ordersFlow(): Flow<List<OrderEntity>>
    @Query("SELECT * FROM orders") suspend fun orders(): List<OrderEntity>
    @Query("SELECT * FROM positions") fun positionsFlow(): Flow<List<PositionEntity>>
    @Query("SELECT * FROM positions") suspend fun positions(): List<PositionEntity>
    @Query("SELECT * FROM fills ORDER BY time DESC") fun fillsFlow(): Flow<List<FillEntity>>
    @Query("SELECT * FROM fills ORDER BY time") suspend fun fills(): List<FillEntity>
    @Query("SELECT * FROM ledger ORDER BY seq, rowid") fun ledgerFlow(): Flow<List<LedgerEntity>>
    @Query("SELECT * FROM ledger ORDER BY seq, rowid") suspend fun ledger(): List<LedgerEntity>

    @Query("SELECT COUNT(*) FROM orders WHERE instrumentId = :id AND status IN ('OPEN','PARTIALLY_FILLED')") suspend fun activeCount(id: String): Int

    @Upsert suspend fun upsertWallet(w: WalletEntity)
    @Upsert suspend fun upsertOrders(o: List<OrderEntity>)
    @Upsert suspend fun upsertPositions(p: List<PositionEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertFills(f: List<FillEntity>): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertLedger(l: List<LedgerEntity>): List<Long>

    /** One atomic unit: a fill, its ledger rows, the wallet change, the order and the position are all committed or none are. */
    @Transaction
    suspend fun persistTrade(w: WalletEntity, orders: List<OrderEntity>, positions: List<PositionEntity>, fills: List<FillEntity>, ledger: List<LedgerEntity>) {
        insertFills(fills); insertLedger(ledger); upsertOrders(orders); upsertPositions(positions); upsertWallet(w)
    }

    @Query("DELETE FROM orders") suspend fun clearOrders()
    @Query("DELETE FROM fills") suspend fun clearFills()
    @Query("DELETE FROM positions") suspend fun clearPositions()
    @Query("DELETE FROM ledger") suspend fun clearLedger()
    @Query("DELETE FROM wallet") suspend fun clearWallet()

    @Transaction
    suspend fun replaceAll(w: WalletEntity, orders: List<OrderEntity>, positions: List<PositionEntity>, fills: List<FillEntity>, ledger: List<LedgerEntity>) {
        clearOrders(); clearFills(); clearPositions(); clearLedger(); clearWallet()
        upsertWallet(w); upsertOrders(orders); upsertPositions(positions); insertFills(fills); insertLedger(ledger)
    }
}

@Dao
interface WatchlistDao {
    @Query("SELECT * FROM watchlists ORDER BY id") fun lists(): Flow<List<WatchlistEntity>>
    @Query("SELECT * FROM watchlist_items ORDER BY addedAt") fun items(): Flow<List<WatchlistItemEntity>>
    @Query("SELECT COUNT(*) FROM watchlists") suspend fun listCount(): Int
    @Insert suspend fun insertList(w: WatchlistEntity): Long
    @Query("UPDATE watchlists SET name = :name WHERE id = :id") suspend fun rename(id: Long, name: String)
    @Query("DELETE FROM watchlists WHERE id = :id") suspend fun deleteList(id: Long)
    @Query("DELETE FROM watchlist_items WHERE watchlistId = :id") suspend fun deleteItemsOf(id: Long)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun addItem(i: WatchlistItemEntity)
    @Query("DELETE FROM watchlist_items WHERE watchlistId = :id AND symbol = :symbol") suspend fun removeItem(id: Long, symbol: String)
    @Transaction suspend fun deleteListWithItems(id: Long) { deleteItemsOf(id); deleteList(id) }
    @Query("DELETE FROM watchlist_items") suspend fun clearItems()
    @Query("DELETE FROM watchlists") suspend fun clearLists()
}

@Dao
interface MarketCacheDao {
    @Upsert suspend fun upsertInstrument(i: InstrumentEntity)
    @Query("SELECT * FROM instruments") suspend fun instruments(): List<InstrumentEntity>
    @Query("SELECT * FROM instruments WHERE symbol = :s") suspend fun instrument(s: String): InstrumentEntity?
    @Upsert suspend fun upsertQuotes(q: List<QuoteCacheEntity>)
    @Query("SELECT * FROM quote_cache") fun quotes(): Flow<List<QuoteCacheEntity>>
    @Query("SELECT * FROM quote_cache") suspend fun quotesNow(): List<QuoteCacheEntity>
}
