package com.papertrade.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [WalletEntity::class, OrderEntity::class, FillEntity::class, PositionEntity::class, LedgerEntity::class,
        WatchlistEntity::class, WatchlistItemEntity::class, InstrumentEntity::class, QuoteCacheEntity::class, MutualFundEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trading(): TradingDao
    abstract fun watchlists(): WatchlistDao
    abstract fun marketCache(): MarketCacheDao
    abstract fun mutualFunds(): MutualFundDao

    companion object {
        /**
         * Migration policy: NEVER use fallbackToDestructiveMigration (that would delete a user's simulation history).
         * Every schema bump must add a Migration here and a MigrationTest (schemas/ is exported for MigrationTestHelper).
         */
        /** 1 -> 2: adds the hand-entered mutual funds table. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `mutual_funds` (`schemeCode` TEXT NOT NULL, `name` TEXT NOT NULL, `units` TEXT NOT NULL, " +
                    "`avgNav` TEXT NOT NULL, `nav` TEXT, `navDate` TEXT, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`schemeCode`))")
            }
        }
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        fun build(context: Context, name: String = "papertrade.db"): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(*MIGRATIONS).build()
    }
}
