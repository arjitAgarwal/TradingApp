package com.papertrade.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CandlestickChart
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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
fun AppHeader(onSearch: () -> Unit, onProfile: () -> Unit, paper: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.ShowChart, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp))
        }
        Row(Modifier.padding(start = 12.dp).weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text("Paper Trade", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            // Real prices, simulated trades: keep that visible even when the title changes.
            if (paper) Text("PAPER", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp).testTag("paper_tag"))
        }
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
    // Fixed tabs, then one tab per watchlist (named by the user, like Groww), then "+" to add a watchlist.
    val fixedTabs = listOf("Explore", "Holdings", "Positions", "Orders")
    val lists by vm.watchlists.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var newList by remember { mutableStateOf(false) }; var openNewList by remember { mutableStateOf(false) }
    LaunchedEffect(lists.size) {
        if (openNewList) { tab = fixedTabs.size + lists.size - 1; openNewList = false } // jump to the list just created
        else if (tab >= fixedTabs.size + lists.size) tab = 0 // its watchlist was deleted
    }
    val selected = tab.coerceIn(0, fixedTabs.size + lists.size - 1) // never index past the tabs while a deletion settles
    val settings by vm.settings.collectAsState(); val feed by vm.feed.collectAsState()
    val auth by vm.fyersAuth.collectAsState()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }
    Scaffold(snackbarHost = { SnackbarHost(snack) }, topBar = {
        Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
            if (settings.dataMode == DataMode.DEMO) DemoBanner()
            AppHeader(onSearch = { tab = 0 }, onProfile = nav.account, paper = settings.dataMode == DataMode.LIVE_PROVIDER)
            MarketTicker(vm, nav.index)
            if (settings.dataMode == DataMode.LIVE_PROVIDER && auth != FyersAuthState.READY) Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().clickable { if (auth == FyersAuthState.NEEDS_LOGIN) nav.fyersLogin() else nav.account() }.testTag("fyers_login_banner")) {
                Text(if (auth == FyersAuthState.NEEDS_LOGIN) "FYERS login needed for live prices. Tap here to log in." else "FYERS is not set up yet. Tap to open settings.",
                    color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(12.dp))
            }
            if (feed.state != FeedState.CONNECTED) Text(feedMessage(feed.state, feed.message), color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
            // Plain text tabs, underline on the selected one; scrolls instead of wrapping "Positions" onto two lines.
            ScrollableTabRow(selectedTabIndex = selected, edgePadding = 8.dp, containerColor = MaterialTheme.colorScheme.surface, divider = { HorizontalDivider() },
                indicator = { pos -> TabRowDefaults.SecondaryIndicator(with(TabRowDefaults) { Modifier.tabIndicatorOffset(pos[selected]) }, height = 3.dp, color = MaterialTheme.colorScheme.onSurface) }) {
                (fixedTabs.map { it to "tab_$it" } + lists.map { it.name to "tab_wl_${it.id}" }).forEachIndexed { i, (t, tag) ->
                    Tab(selected == i, { tab = i }, selectedContentColor = MaterialTheme.colorScheme.onSurface, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = { Text(t, fontSize = 16.sp, fontWeight = if (selected == i) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, softWrap = false) }, modifier = Modifier.testTag(tag))
                }
                Tab(false, { newList = true }, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant, icon = { Icon(Icons.Default.Add, "New watchlist") }, modifier = Modifier.testTag("tab_add_watchlist"))
            }
        }
    }, bottomBar = { BottomBar(onFno = { nav.chain(Underlying.NIFTY) }, onUnavailable = { vm.messages.tryEmit("$it isn't part of this paper-trading app") }) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (selected) {
                0 -> ExploreScreen(vm, nav); 1 -> HoldingsScreen(vm, nav); 2 -> PositionsScreen(vm); 3 -> OrdersScreen(vm)
                else -> lists.getOrNull(selected - fixedTabs.size)?.let { WatchlistScreen(vm, nav, it, canDelete = lists.size > 1) }
            }
        }
    }
    if (newList) NameDialog("New watchlist", "") { name -> newList = false; name?.let { openNewList = true; vm.createWatchlist(it) } }
}

/** One watchlist tab: "N items" with rename/delete, then its instruments. Tap a row to open it; ★ removes it. */
@Composable
fun WatchlistScreen(vm: AppVM, nav: Nav, list: com.papertrade.app.data.repo.Watchlist, canDelete: Boolean) {
    val quotes by vm.quotes.collectAsState(); val now by vm.now.collectAsState()
    var renaming by remember { mutableStateOf(false) }; var confirmDelete by remember { mutableStateOf(false) }
    DisposableEffect(list.id, list.items.size) { vm.subscribe("watchlist", list.items.map { it.symbol }.toSet()); onDispose { vm.unsubscribe("watchlist") } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${list.items.size} ${if (list.items.size == 1) "item" else "items"}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton({ renaming = true }, Modifier.testTag("rename_watchlist")) { Icon(Icons.Default.Edit, "Rename watchlist") }
            if (canDelete) IconButton({ confirmDelete = true }, Modifier.testTag("delete_watchlist")) { Icon(Icons.Default.DeleteOutline, "Delete watchlist") }
        }
        if (list.items.isEmpty()) EmptyState(Icons.Default.StarBorder, "This watchlist is empty", "Search in Explore and tap ☆ to add a stock.")
        else LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp)) {
            items(list.items, key = { it.symbol }) { item ->
                StockRow(item.symbol, item.name, quotes[item.symbol], freshnessOf(quotes[item.symbol], now, vm.maxAgeSec()), true,
                    onClick = { if (item.symbol.endsWith("-EQ")) nav.stock(item.symbol) else nav.contract(item.symbol) },
                    onToggle = { vm.removeFromWatchlist(list.id, item.symbol) })
            }
        }
    }
    if (renaming) NameDialog("Rename watchlist", list.name) { name -> renaming = false; name?.let { vm.renameWatchlist(list.id, it) } }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete \"${list.name}\"?") },
        text = { Text("The watchlist and its ${list.items.size} items will be removed.") },
        confirmButton = { TextButton({ confirmDelete = false; vm.deleteWatchlist(list.id) }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
}

/** Text prompt for a watchlist name; calls back with the name, or null if cancelled. */
@Composable
private fun NameDialog(title: String, initial: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = { onDone(null) }, title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("e.g. Fut") }, modifier = Modifier.testTag("watchlist_name")) },
        confirmButton = { TextButton({ onDone(text.trim()) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton({ onDone(null) }) { Text("Cancel") } })
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
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<UiState<List<com.papertrade.app.data.market.InstrumentInfo>>?>(null) }
    var picking by remember { mutableStateOf<com.papertrade.app.data.market.InstrumentInfo?>(null) } // stock whose ☆ was tapped
    val provider by vm.provider.collectAsState()
    LaunchedEffect(query, provider) {
        if (query.length < 2) { results = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(350); results = UiState.Loading
        results = vm.search(query).fold({ UiState.Success(it) }, { it.toUi() })
    }
    DisposableEffect(results) {
        val syms = (results as? UiState.Success)?.data?.take(10)?.map { it.symbol } ?: emptyList()
        vm.subscribe("explore", syms.toSet()); onDispose { vm.unsubscribe("explore") }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Market overview", fontWeight = FontWeight.SemiBold) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Underlying.values().forEach { u -> val q = quotes[u.indexSymbol]
                    OutlinedCard(Modifier.weight(1f).clickable { nav.index(u) }.testTag("card_${u.name}")) { Column(Modifier.padding(12.dp)) {
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
                StockRow(i.symbol, i.name, quotes[i.symbol], freshnessOf(quotes[i.symbol], now, vm.maxAgeSec()), lists.any { w -> w.items.any { it.symbol == i.symbol } },
                    onClick = { vm.info(i.symbol) ?: vm.register(i); nav.stock(i.symbol) },
                    onToggle = { picking = i })
            }
        } }
        if (results == null) item { Text("Your watchlists are the tabs after Orders. Search for a stock and tap ☆ to add it to one.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    picking?.let { i -> WatchlistPicker(lists, i.symbol, onDismiss = { picking = null }) { w, add ->
        if (add) { vm.register(i); vm.addToWatchlist(w.id, i.symbol, i.name) } else vm.removeFromWatchlist(w.id, i.symbol)
    } }
}

/** "Add to watchlist" sheet: one checkbox per watchlist; ticking adds, unticking removes. */
@Composable
private fun WatchlistPicker(lists: List<com.papertrade.app.data.repo.Watchlist>, symbol: String, onDismiss: () -> Unit, onToggle: (com.papertrade.app.data.repo.Watchlist, Boolean) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add to watchlist") }, text = {
        Column { lists.forEach { w -> val inList = w.items.any { it.symbol == symbol }
            Row(Modifier.fillMaxWidth().clickable { onToggle(w, !inList) }.testTag("pick_${w.id}"), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(inList, { onToggle(w, it) }); Text(w.name)
            } } }
    }, confirmButton = { TextButton(onDismiss) { Text("Done") } })
}

@Composable
fun StockRow(symbol: String, name: String, q: MarketQuote?, f: Freshness, starred: Boolean, onClick: () -> Unit, onToggle: () -> Unit) {
    // Flat row with a hairline divider (Groww style). Old prices are greyed instead of tagged.
    val fresh = f == Freshness.LIVE || f == Freshness.DEMO
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(start = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(symbol.substringAfter(':').removeSuffix("-EQ"), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.rupee(q?.ltp), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = if (fresh) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${Fmt.signed(q?.change)} (${Fmt.pct(q?.changePct)})", fontSize = 12.sp, color = Fmt.color(q?.change, MaterialTheme.colorScheme.onSurfaceVariant))
            }
            IconButton(onToggle) { Icon(if (starred) Icons.Default.Star else Icons.Default.StarBorder, if (starred) "Remove from watchlist" else "Add to watchlist", tint = if (starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
