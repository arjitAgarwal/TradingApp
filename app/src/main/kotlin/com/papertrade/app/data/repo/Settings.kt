package com.papertrade.app.data.repo

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.papertrade.app.data.market.DataMode
import com.papertrade.app.data.market.LiveSource
import com.papertrade.core.ChargeModel
import com.papertrade.core.ExecutionConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.time.Duration

private val Context.dataStore by preferencesDataStore("settings")

data class AppSettings(
    val dataMode: DataMode = DataMode.DEMO, // safe default: no credentials needed, clearly labelled
    val maxQuoteAgeSec: Int = 15,
    val slippageBps: Int = 0,
    val chargesEnabled: Boolean = true,
    val partialFillCap: Int = 0, // 0 = off
    val liveSource: LiveSource = LiveSource.FYERS,
    /** The FYERS account is on the paid Prime plan (much higher data limits). */
    val fyersPrime: Boolean = false,
) {
    fun toExecutionConfig() = ExecutionConfig(
        maxQuoteAge = Duration.ofSeconds(maxQuoteAgeSec.toLong()),
        ltpSlippageBps = BigDecimal(slippageBps),
        maxQtyPerFill = partialFillCap.takeIf { it > 0 },
        charges = if (chargesEnabled) ChargeModel.ILLUSTRATIVE else ChargeModel.ZERO,
    )
}

class SettingsStore(private val context: Context) {
    private val MODE = stringPreferencesKey("data_mode"); private val AGE = intPreferencesKey("quote_age")
    private val SLIP = intPreferencesKey("slip_bps"); private val CHG = booleanPreferencesKey("charges"); private val PART = intPreferencesKey("partial_cap")
    private val SOURCE = stringPreferencesKey("live_source"); private val PRIME = booleanPreferencesKey("fyers_prime")
    val settings: Flow<AppSettings> = context.dataStore.data.map { p: Preferences ->
        val d = AppSettings()
        AppSettings(p[MODE]?.let { runCatching { DataMode.valueOf(it) }.getOrNull() } ?: d.dataMode, p[AGE] ?: d.maxQuoteAgeSec, p[SLIP] ?: d.slippageBps, p[CHG] ?: d.chargesEnabled, p[PART] ?: d.partialFillCap,
            p[SOURCE]?.let { runCatching { LiveSource.valueOf(it) }.getOrNull() } ?: d.liveSource, p[PRIME] ?: d.fyersPrime)
    }
    suspend fun update(s: AppSettings) = context.dataStore.edit { p ->
        p[MODE] = s.dataMode.name; p[AGE] = s.maxQuoteAgeSec; p[SLIP] = s.slippageBps; p[CHG] = s.chargesEnabled; p[PART] = s.partialFillCap; p[SOURCE] = s.liveSource.name
        p[PRIME] = s.fyersPrime
    }
}
