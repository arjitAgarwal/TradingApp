package com.papertrade.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [WalletEntity::class, OrderEntity::class, FillEntity::class, PositionEntity::class, LedgerEntity::class,
        WatchlistEntity::class, WatchlistItemEntity::class, InstrumentEntity::class, QuoteCacheEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trading(): TradingDao
    abstract fun watchlists(): WatchlistDao
    abstract fun marketCache(): MarketCacheDao

    companion object {
        /**
         * Migration policy: NEVER use fallbackToDestructiveMigration (that would delete a user's simulation history).
         * Every schema bump must add a Migration here and a MigrationTest (schemas/ is exported for MigrationTestHelper).
         * Version 1 is the first release, so the list is intentionally empty.
         */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun build(context: Context, name: String = "papertrade.db"): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*MIGRATIONS).build()
    }
}
