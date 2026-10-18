
package com.papertrade.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** All BigDecimal values are stored as plain strings (lossless); instants as epoch millis. */
@Serializable
@Entity(tableName = "wallet")
data class WalletEntity(@PrimaryKey val id: Int = 1, val startingCapital: String, val cash: String, val reservedMargin: String)

@Serializable
@Entity(tableName = "orders", indices = [Index("instrumentId"), Index("status")])
data class OrderEntity(
    @PrimaryKey val id: String, val instrumentId: String, val side: String, val type: String, val quantity: Int, val limitPrice: String?,
    val filledQuantity: Int, val avgFillPrice: String?, val status: String, val createdAt: Long, val updatedAt: Long, val executedAt: Long?,
    val rejectReason: String?, val lastQuoteTs: Long?, val fillSeq: Int,
)

/** Fill id is the primary key: inserting the same fill twice is a no-op (exactly-once application). */
@Serializable
@Entity(tableName = "fills", indices = [Index("orderId"), Index("instrumentId")])
data class FillEntity(
    @PrimaryKey val id: String, val orderId: String, val instrumentId: String, val side: String, val quantity: Int,
    val price: String, val charges: String, val realizedGross: String, val time: Long, val quoteSource: String,
)

@Serializable
@Entity(tableName = "positions")
data class PositionEntity(@PrimaryKey val instrumentId: String, val quantity: Int, val avgPrice: String, val realizedGross: String, val charges: String)

@Serializable
@Entity(tableName = "ledger")
data class LedgerEntity(@PrimaryKey val id: String, val type: String, val amount: String, val refFillId: String?, val time: Long, val note: String, val seq: Long = 0)

@Serializable
@Entity(tableName = "watchlists")
data class WatchlistEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Serializable
@Entity(tableName = "watchlist_items", primaryKeys = ["watchlistId", "symbol"], indices = [Index("symbol")])
data class WatchlistItemEntity(val watchlistId: Long, val symbol: String, val name: String, val addedAt: Long)

@Serializable
@Entity(tableName = "instruments")
data class InstrumentEntity(
    @PrimaryKey val symbol: String, val name: String, val segment: String, val lotSize: Int, val tickSize: String,
    val expiry: String?, val strike: String?, val optionType: String?, val underlying: String?, val savedAt: Long,
)

@Serializable
@Entity(tableName = "quote_cache")
data class QuoteCacheEntity(
    @PrimaryKey val symbol: String, val ltp: String, val bid: String?, val ask: String?, val prevClose: String?, val change: String?,
    val changePct: String?, val open: String?, val high: String?, val low: String?, val timestamp: Long, val originalSource: String,
)
