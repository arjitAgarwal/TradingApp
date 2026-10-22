package com.papertrade.app

import android.app.Application
import com.papertrade.app.data.db.AppDatabase
import com.papertrade.app.data.market.DataMode
import com.papertrade.app.data.market.DemoProvider
import com.papertrade.app.data.market.FyersProvider
import com.papertrade.app.data.market.LiveSource
import com.papertrade.app.data.market.UpstoxProvider
import com.papertrade.app.data.repo.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Manual dependency container (no DI framework: fewer moving parts, easy to fake in tests). */
class AppContainer(app: Application, providerOverride: ((DataMode, LiveSource) -> com.papertrade.app.data.market.MarketDataProvider)? = null, dbName: String = "papertrade.db") {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.build(app, dbName)
    val settingsStore = SettingsStore(app)
    val fyersAuth = FyersAuth(app)
    val upstoxAuth = UpstoxAuth(app)
    private val demo = DemoProvider()
    private val fyers = FyersProvider(fyersAuth, app.cacheDir)
    private val upstox = UpstoxProvider(upstoxAuth, app.cacheDir)
    val trading = TradingRepository(db, settings = { settingsStore.settings.first() })
    val watchlists = WatchlistRepository(db)
    /** Contract metadata seen in chains/futures lists, so order tickets never need to guess lot size or tick. */
    val contracts = java.util.concurrent.ConcurrentHashMap<String, com.papertrade.app.data.market.InstrumentInfo>()
    val hub = QuoteHub(scope, providerOverride ?: { mode, source -> if (mode == DataMode.DEMO) demo else if (source == LiveSource.UPSTOX) upstox else fyers }, settingsStore.settings, trading, db.marketCache())
    init {
        scope.launch { trading.init(); watchlists.ensureDefault() }
        scope.launch { runCatching { fyersAuth.accessToken() } } // renew at launch so the login prompt (if any) shows straight away
        scope.launch { settingsStore.settings.collect { fyers.prime = it.fyersPrime } }
    }
}

class PaperTradeApp : Application() {
    lateinit var container: AppContainer
    override fun onCreate() { super.onCreate(); container = AppContainer(this) }
}
