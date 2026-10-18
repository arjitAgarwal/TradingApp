package com.papertrade.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.CandlestickChart
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papertrade.app.data.market.DataMode
import com.papertrade.app.data.market.FeedState
import com.papertrade.app.data.market.MarketQuote
import com.papertrade.app.data.market.Underlying
import com.papertrade.app.data.repo.FyersAuthState
import com.papertrade.app.ui.*
import com.papertrade.app.ui.components.*

/** One thin, sideways-scrolling line: "NIFTY 50  24,913.50 [+148.80]   SENSEX …". Tap an index to open it. */
@Composable
fun MarketTicker(vm: AppVM, onIndex: (Underlying) -> Unit) {
    val quotes by vm.quotes.collectAsState(); val now by vm.now.collectAsState()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Underlying.values().forEach { u ->
            val q = quotes[u.indexSymbol]
            // Stale/cached/missing prices are greyed so an old number never passes for a live one.
            val fresh = freshnessOf(q, now, vm.maxAgeSec()).let { it == Freshness.LIVE || it == Freshness.DEMO }
            Row(Modifier.clickable { onIndex(u) }.testTag("ticker_${u.name}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(u.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(Fmt.inr(q?.ltp), fontSize = 13.sp, color = if (fresh) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                ChangeBadge(q?.change)
            }
        }
    }
}

/** Green/red pill with the signed change, like "+148.80". */
@Composable
fun ChangeBadge(change: java.math.BigDecimal?) {
    val c = Fmt.color(change, MaterialTheme.colorScheme.onSurfaceVariant)
    Text(Fmt.signed(change), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c,
        modifier = Modifier.background(c.copy(alpha = 0.12f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
}

/** Compact one-row header. Logo and title are placeholders until the header design is decided. */
@Composable
fun AppHeader(onSearch: () -> Unit, onProfile: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ShowChart, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
        }
        Text("Paper Trade", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp).weight(1f))
        IconButton(onSearch, Modifier.testTag("header_search")) { Icon(Icons.Default.Search, "Search") }
        IconButton(onProfile, Modifier.testTag("open_account")) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Person, "Account", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun MainScreen(vm: AppVM, nav: Nav) {
    val tabs = listOf("Explore", "Holdings", "Positions", "Orders")
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val settings by vm.settings.collectAsState(); val feed by vm.feed.collectAsState()
    val auth by vm.fyersAuth.collectAsState()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }
    Scaffold(snackbarHost = { SnackbarHost(snack) }, topBar = {
        Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
            if (settings.dataMode == DataMode.DEMO) DemoBanner()
            AppHeader(onSearch = { tab = 0 }, onProfile = nav.account)
            MarketTicker(vm, nav.index)
            if (settings.dataMode == DataMode.LIVE_PROVIDER && auth != FyersAuthState.READY) Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().clickable { if (auth == FyersAuthState.NEEDS_LOGIN) nav.fyersLogin() else nav.account() }.testTag("fyers_login_banner")) {
                Text(if (auth == FyersAuthState.NEEDS_LOGIN) "FYERS login needed for live prices. Tap here to log in." else "FYERS is not set up yet. Tap to open settings.",
                    color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(12.dp))
            }
            if (feed.state != FeedState.CONNECTED) Text(feedMessage(feed.state, feed.message), color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
            // Plain text tabs, underline on the selected one; scrolls instead of wrapping "Positions" onto two lines.
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp, containerColor = MaterialTheme.colorScheme.surface, divider = { HorizontalDivider() },
                indicator = { pos -> TabRowDefaults.SecondaryIndicator(with(TabRowDefaults) { Modifier.tabIndicatorOffset(pos[tab]) }, height = 3.dp, color = MaterialTheme.colorScheme.onSurface) }) {
                tabs.forEachIndexed { i, t ->
                    Tab(tab == i, { tab = i }, selectedContentColor = MaterialTheme.colorScheme.onSurface, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = { Text(t, fontSize = 16.sp, fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, softWrap = false) }, modifier = Modifier.testTag("tab_$t"))
                }
            }
        }
    }, bottomBar = { BottomBar(onFno = { nav.chain(Underlying.NIFTY) }, onUnavailable = { vm.messages.tryEmit("$it isn't part of this paper-trading app") }) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) { 0 -> ExploreScreen(vm, nav); 1 -> HoldingsScreen(vm, nav); 2 -> PositionsScreen(vm); else -> OrdersScreen(vm) }
        }
    }
}

/** Stocks is this screen; F&O opens the NIFTY option chain; Mutual Funds and Loans don't exist here, so they only explain that. */
@Composable
private fun BottomBar(onFno: () -> Unit, onUnavailable: (String) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        listOf(Triple("Stocks", Icons.AutoMirrored.Filled.ShowChart, {}), Triple("F&O", Icons.Default.CandlestickChart, onFno),
            Triple("Mutual Funds", Icons.Default.PieChart, { onUnavailable("Mutual Funds") }), Triple("Loans", Icons.Default.Payments, { onUnavailable("Loans") }))
            .forEachIndexed { i, (label, icon, onClick) ->
                NavigationBarItem(selected = i == 0, onClick = onClick, icon = { Icon(icon, null) }, label = { Text(label, fontSize = 11.sp, maxLines = 1) },
                    modifier = Modifier.testTag("bottom_$label"))
            }
    }
}

private fun feedMessage(s: FeedState, m: String?) = when (s) {
    FeedState.NOT_CONFIGURED -> "Live data not configured: ${m ?: "add credentials in Account → Settings"}"
    FeedState.OFFLINE -> "Offline — showing saved prices with their original timestamps. ${m ?: ""}"
    FeedState.RECONNECTING -> "Reconnecting… ${m ?: ""}"
    FeedState.ERROR -> "Data error: ${m ?: ""}"
    FeedState.CONNECTED -> ""
}

class Nav(val index: (Underlying) -> Unit, val stock: (String) -> Unit, val chain: (Underlying) -> Unit, val futures: (Underlying) -> Unit,
          val contract: (String) -> Unit, val account: () -> Unit, val back: () -> Unit, val fyersLogin: () -> Unit = {})

@Composable
fun ExploreScreen(vm: AppVM, nav: Nav) {
    val lists by vm.watchlists.collectAsState(); val quotes by vm.quotes.collectAsState(); val now by vm.now.collectAsState()
    var selected by rememberSaveable { mutableLongStateOf(-1L) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<UiState<List<com.papertrade.app.data.market.InstrumentInfo>>?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) } // "new" or "rename"
    val current = lists.firstOrNull { it.id == selected } ?: lists.firstOrNull()
    val provider by vm.provider.collectAsState()
    LaunchedEffect(query, provider) {
        if (query.length < 2) { results = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(350); results = UiState.Loading
        results = vm.search(query).fold({ UiState.Success(it) }, { it.toUi() })
    }
    DisposableEffect(current?.items?.size, results) {
        val syms = (current?.items?.map { it.symbol } ?: emptyList()) + ((results as? UiState.Success)?.data?.take(10)?.map { it.symbol } ?: emptyList())
        vm.subscribe("explore", syms.toSet()); onDispose { vm.unsubscribe("explore") }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Market overview", fontWeight = FontWeight.SemiBold) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Underlying.values().forEach { u -> val q = quotes[u.indexSymbol]
                    Card(Modifier.weight(1f).clickable { nav.index(u) }.testTag("card_${u.name}")) { Column(Modifier.padding(12.dp)) {
                        Text(u.displayName); Text(Fmt.inr(q?.ltp), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(Fmt.pct(q?.changePct), color = Fmt.color(q?.changePct, MaterialTheme.colorScheme.onSurface))
                        Text("Option chain · Futures →", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    } } }
            }
        }
        item { OutlinedTextField(query, { query = it }, label = { Text("Search stocks") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("search")) }
        results?.let { r -> when (r) {
            UiState.Loading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            is UiState.Error -> item { Text(r.message, color = MaterialTheme.colorScheme.error) }
            is UiState.Success -> if (r.data.isEmpty()) item { Text("No matching stocks") } else items(r.data, key = { it.symbol }) { i ->
                StockRow(i.symbol, i.name, quotes[i.symbol], freshnessOf(quotes[i.symbol], now, vm.maxAgeSec()), current != null && current.items.any { it.symbol == i.symbol },
                    onClick = { vm.info(i.symbol) ?: vm.register(i); nav.stock(i.symbol) },
                    onToggle = { current?.let { w -> if (w.items.any { it.symbol == i.symbol }) vm.removeFromWatchlist(w.id, i.symbol) else { vm.register(i); vm.addToWatchlist(w.id, i.symbol, i.name) } } })
            }
        } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                lists.forEach { w -> FilterChip(current?.id == w.id, { selected = w.id }, { Text(w.name) }) }
                AssistChip(onClick = { dialog = "new" }, label = { Text("+ New") })
            }
        }
        if (current != null) {
            item { Row { TextButton({ dialog = "rename" }) { Text("Rename") }; if (lists.size > 1) TextButton({ vm.deleteWatchlist(current.id); selected = -1 }) { Text("Delete list") } } }
            if (current.items.isEmpty()) item { Text("This watchlist is empty. Search for a stock and tap ☆ to add it.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(current.items, key = { it.symbol }) { it ->
                StockRow(it.symbol, it.name, quotes[it.symbol], freshnessOf(quotes[it.symbol], now, vm.maxAgeSec()), true, onClick = { nav.stock(it.symbol) }, onToggle = { vm.removeFromWatchlist(current.id, it.symbol) })
            }
        }
    }
    dialog?.let { mode ->
        var text by remember { mutableStateOf(if (mode == "rename") current?.name ?: "" else "") }
        AlertDialog(onDismissRequest = { dialog = null }, title = { Text(if (mode == "new") "New watchlist" else "Rename watchlist") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true) },
            confirmButton = { TextButton({ if (mode == "new") vm.createWatchlist(text) else current?.let { vm.renameWatchlist(it.id, text) }; dialog = null }) { Text("Save") } },
            dismissButton = { TextButton({ dialog = null }) { Text("Cancel") } })
    }
}

@Composable
fun StockRow(symbol: String, name: String, q: MarketQuote?, f: Freshness, starred: Boolean, onClick: () -> Unit, onToggle: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(symbol.substringAfter(':').removeSuffix("-EQ"), fontWeight = FontWeight.SemiBold); Text(name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FreshnessChip(f, q?.let { Fmt.time(it.timestamp).substringAfter(", ") })
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.inr(q?.ltp), fontWeight = FontWeight.Bold)
                Text("${Fmt.signed(q?.change)} (${Fmt.pct(q?.changePct)})", fontSize = 12.sp, color = Fmt.color(q?.change, MaterialTheme.colorScheme.onSurface))
            }
            TextButton(onToggle) { Text(if (starred) "★" else "☆", fontSize = 20.sp) }
        }
    }
}
