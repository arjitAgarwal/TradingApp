package com.papertrade.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.papertrade.app.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Instrumented. Version 1 data must survive the move to 2, and the new table must match Room's schema. */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val name = "migration-test"
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun migrate1To2KeepsWalletAndAddsMutualFunds() {
        helper.createDatabase(name, 1).apply { execSQL("INSERT INTO wallet (id, startingCapital, cash, reservedMargin) VALUES (1, '1354510', '1200000', '0')"); close() }
        val db = helper.runMigrationsAndValidate(name, 2, true, AppDatabase.MIGRATION_1_2)
        db.query("SELECT cash FROM wallet WHERE id = 1").use { it.moveToFirst(); assertEquals("1200000", it.getString(0)) }
        db.query("SELECT COUNT(*) FROM mutual_funds").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }
}
