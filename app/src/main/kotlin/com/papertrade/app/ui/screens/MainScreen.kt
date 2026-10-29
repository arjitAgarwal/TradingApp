package com.papertrade.app.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CandlestickChart
import androidx.compose.material.icons.filled.Cyclone
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Payments
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import com.papertrade.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papertrade.app.data.market.DataMode
import com.papertrade.app.data.market.LiveSource
import com.papertrade.app.data.market.FeedState
import com.papertrade.app.data.market.MarketQuote
import com.papertrade.app.data.market.Underlying
import com.papertrade.app.data.db.WatchlistItemEntity
import com.papertrade.app.data.repo.LoginState
import com.papertrade.app.ui.*
import com.papertrade.app.ui.components.*
import com.papertrade.app.ui.theme.Demo
import com.papertrade.app.ui.theme.Gain
import com.papertrade.app.ui.theme.Loss
import com.papertrade.core.Side
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.ui.graphics.Color

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

/**
 * Groww-style header: round logo and "Stocks" on the left; search, a look-only swirl and the profile avatar on the right.
 */
@Composable
fun AppHeader(onSearch: () -> Unit, onSwirl: () -> Unit, onProfile: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.alpha_logo), "ALPHA", Modifier.size(40.dp).clip(CircleShape).testTag("header_logo"))
        Text("Stocks", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 14.dp).weight(1f))
        IconButton(onSearch, Modifier.testTag("header_search")) { Icon(Icons.Default.Search, "Search", Modifier.size(26.dp)) }
        IconButton(onSwirl, Modifier.testTag("header_swirl")) { Icon(Icons.Default.Cyclone, "Coming soon", Modifier.size(26.dp)) }
        IconButton(onProfile, Modifier.testTag("open_account")) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Person, "Account", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
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
    val fyersAuth by vm.fyersAuth.collectAsState(); val upstoxAuth by vm.upstoxAuth.collectAsState()
    val upstox = settings.liveSource == LiveSource.UPSTOX
    val auth = if (upstox) upstoxAuth else fyersAuth; val broker = settings.liveSource.displayName
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.messages.collect { snack.showSnackbar(it) } }
    Scaffold(snackbarHost = { SnackbarHost(snack) }, topBar = {
        Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
            // Android 15+ draws the app under the status bar: keep the clock clear, in the banner's colour in demo mode.
            Spacer(Modifier.fillMaxWidth().background(if (settings.dataMode == DataMode.DEMO) Demo else MaterialTheme.colorScheme.surface).windowInsetsTopHeight(WindowInsets.statusBars))
            if (settings.dataMode == DataMode.DEMO) DemoBanner()
            AppHeader(onSearch = { tab = 0 }, onSwirl = { vm.messages.tryEmit("Coming soon") }, onProfile = nav.account)
            MarketTicker(vm, nav.index)
            if (settings.dataMode == DataMode.LIVE_PROVIDER && auth != LoginState.READY) Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().clickable { if (auth != LoginState.NEEDS_LOGIN) nav.account() else if (upstox) nav.upstoxLogin() else nav.fyersLogin() }.testTag("fyers_login_banner")) {
                Text(if (auth == LoginState.NEEDS_LOGIN) "$broker login needed for live prices. Tap here to log in." else "$broker is not set up yet. Tap to open settings.",
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
    }, bottomBar = { BottomBar(onFno = { nav.chain(Underlying.NIFTY) }, onAccount = nav.account, onUnavailable = { vm.messages.tryEmit("$it isn't part of this paper-trading app") }) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (selected) {
                0 -> ExploreScreen(vm, nav); 1 -> HoldingsScreen(vm, nav); 2 -> PositionsScreen(vm, nav); 3 -> OrdersScreen(vm)
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
    var action by remember { mutableStateOf<WatchlistItemEntity?>(null) }
    val sparks by vm.sparks.collectAsState()
    DisposableEffect(list.id, list.items.size) { vm.subscribe("watchlist", list.items.map { it.symbol }.toSet()); onDispose { vm.unsubscribe("watchlist") } }
    LaunchedEffect(list.id, list.items.size) { while (true) { vm.loadSparks(list.items.map { it.symbol }); kotlinx.coroutines.delay(60_000) } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${list.items.size} ${if (list.items.size == 1) "item" else "items"}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton({ renaming = true }, Modifier.testTag("rename_watchlist")) { Icon(Icons.Default.Edit, "Rename watchlist") }
            if (canDelete) IconButton({ confirmDelete = true }, Modifier.testTag("delete_watchlist")) { Icon(Icons.Default.DeleteOutline, "Delete watchlist") }
        }
        if (list.items.isEmpty()) EmptyState(Icons.Default.StarBorder, "This watchlist is empty", "Search in Explore and tap ☆ to add a stock.")
        else {
            // Price column as wide as the widest price or change line in the list: charts still line up down the list, the
            // percent is never cut off, and short values leave the name more room for the strike.
            val measurer = rememberTextMeasurer(); val style = LocalTextStyle.current; val density = LocalDensity.current
            val priceCol = list.items.maxOfOrNull { val q = quotes[it.symbol]
                maxOf(measurer.measure(Fmt.changeLine(q?.change, q?.changePct), style.copy(fontSize = 13.sp)).size.width,
                    measurer.measure(Fmt.rupee(q?.ltp), style.copy(fontSize = 15.sp)).size.width) }
                ?.let { with(density) { it.toDp() } + 2.dp }?.coerceAtLeast(80.dp) ?: 80.dp
            LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp)) {
                items(list.items, key = { it.symbol }) { item -> Box(Modifier.testTag("wl_item_${item.symbol}")) {
                    WatchRow(Fmt.labelParts(item.symbol, vm.info(item.symbol)), quotes[item.symbol], freshnessOf(quotes[item.symbol], now, vm.maxAgeSec()),
                        sparks[item.symbol].orEmpty(), priceCol) { action = item } }
                }
            }
        }
    }
    action?.let { item -> QuickActionSheet(vm, item.symbol, quotes[item.symbol], { action = null },
        onOpen = { action = null; if (item.symbol.endsWith("-EQ")) nav.stock(item.symbol) else nav.contract(item.symbol) },
        onTrade = { side -> action = null; nav.order(item.symbol, side) },
        onRemove = { action = null; vm.removeFromWatchlist(list.id, item.symbol) }) }
    if (renaming) NameDialog("Rename watchlist", list.name) { name -> renaming = false; name?.let { vm.renameWatchlist(list.id, it) } }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete \"${list.name}\"?") },
        text = { Text("The watchlist and its ${list.items.size} items will be removed.") },
        confirmButton = { TextButton({ confirmDelete = false; vm.deleteWatchlist(list.id) }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancel") } })
}

/**
 * Groww watchlist row: name in bold over "Call"/"Put"/"Fut" (equities: just the ticker), today's mini chart in a
 * fixed column, price over change. [priceCol] is shared by the whole list so the charts line up.
 */
@Composable
private fun WatchRow(label: Pair<String, String?>, q: MarketQuote?, f: Freshness, closes: List<java.math.BigDecimal>, priceCol: Dp, onClick: () -> Unit) {
    val fresh = f == Freshness.LIVE || f == Freshness.DEMO
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label.first, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                label.second?.let { Text(it, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp)) }
            }
            Sparkline(closes, q?.ltp, q?.prevClose, q?.change, Modifier.padding(horizontal = 6.dp).size(width = 44.dp, height = 20.dp))
            Column(Modifier.width(priceCol), horizontalAlignment = Alignment.End) {
                Text(Fmt.rupee(q?.ltp), fontSize = 15.sp, color = if (fresh) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                Text(Fmt.changeLine(q?.change, q?.changePct), fontSize = 13.sp, color = Fmt.color(q?.change, MaterialTheme.colorScheme.onSurfaceVariant),
                    maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 6.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Groww-style sheet shown on tapping a watchlist row: name card (tap to open full details), Set alert, Remove, Sell / Buy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickActionSheet(vm: AppVM, symbol: String, q: MarketQuote?, onClose: () -> Unit, onOpen: () -> Unit, onTrade: (Side) -> Unit, onRemove: () -> Unit) {
    var info by remember(symbol) { mutableStateOf(vm.info(symbol)) }
    LaunchedEffect(symbol) { if (info == null && symbol.endsWith("-EQ")) vm.equityInfo(symbol).onSuccess { info = it; vm.register(it) } }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).testTag("quick_actions")) {
            OutlinedCard(Modifier.fillMaxWidth().clickable(onClick = onOpen).testTag("quick_open"), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Fmt.label(symbol, info), fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Open details")
                    Spacer(Modifier.width(12.dp))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Fmt.rupee(q?.ltp), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("${Fmt.signed(q?.change)} (${Fmt.pct(q?.changePct)})", fontSize = 13.sp, color = Fmt.color(q?.change, MaterialTheme.colorScheme.onSurfaceVariant))
                    }
                }
            }
            // Alerts aren't built yet; the row is here so the sheet matches the target layout.
            Row(Modifier.fillMaxWidth().clickable { vm.messages.tryEmit("Price alerts are coming soon") }.padding(vertical = 16.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.NotificationsNone, null); Spacer(Modifier.width(16.dp)); Text("Set alert", fontSize = 16.sp)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            // Rows have no star (as in Groww), so removal lives here.
            Row(Modifier.fillMaxWidth().clickable(onClick = onRemove).padding(vertical = 16.dp, horizontal = 4.dp).testTag("quick_remove"), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.DeleteOutline, null); Spacer(Modifier.width(16.dp)); Text("Remove from watchlist", fontSize = 16.sp)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (info == null) Text("Contract details unavailable, so this can't be traded right now.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button({ onTrade(Side.SELL) }, Modifier.weight(1f).height(52.dp).testTag("btn_sell"), enabled = info != null, shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Loss, contentColor = Color.White)) { Text("Sell", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                Button({ onTrade(Side.BUY) }, Modifier.weight(1f).height(52.dp).testTag("btn_buy"), enabled = info != null, shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Gain, contentColor = Color.White)) { Text("Buy", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
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

/** Stocks is this screen; F&O opens the NIFTY option chain; Mutual Funds doesn't exist here, so it only explains that; Loans opens Account (no loans in a paper app). */
@Composable
private fun BottomBar(onFno: () -> Unit, onAccount: () -> Unit, onUnavailable: (String) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        listOf(Triple("Stocks", Icons.AutoMirrored.Filled.ShowChart, {}), Triple("F&O", Icons.Default.CandlestickChart, onFno),
            Triple("Mutual Funds", Icons.Default.PieChart, { onUnavailable("Mutual Funds") }), Triple("Loans", Icons.Default.Payments, onAccount))
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
          val contract: (String) -> Unit, val account: () -> Unit, val back: () -> Unit, val fyersLogin: () -> Unit = {}, val upstoxLogin: () -> Unit = {},
          val order: (String, Side) -> Unit = { _, _ -> })

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
