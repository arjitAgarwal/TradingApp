package com.papertrade.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.StarBorder
import com.papertrade.app.ui.theme.Accent
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PieChart
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

private enum class Section { HOME, WATCHLIST, PORTFOLIO, ORDERS }

/**
 * Search bar, the two index cards and a five-item bottom bar (HOME, WATCHLIST, PORTFOLIO, ORDERS, ACCOUNT). The app
 * opens on WATCHLIST; ACCOUNT opens the settings screen.
 */
@Composable
fun MainScreen(vm: AppVM, nav: Nav) {
    var section by rememberSaveable { mutableStateOf(Section.WATCHLIST) }; var ordersTab by rememberSaveable { mutableIntStateOf(0) }
    val provider by vm.provider.collectAsState()
    LaunchedEffect(provider) { vm.loadExpiries() }
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
            if (section == Section.ORDERS) OrdersTabs(ordersTab) { ordersTab = it } else SearchBar { section = Section.HOME }
            IndexCards(vm, nav.index)
            HorizontalDivider()
            if (settings.dataMode == DataMode.LIVE_PROVIDER && auth != LoginState.READY) Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().clickable { if (auth != LoginState.NEEDS_LOGIN) nav.account() else if (upstox) nav.upstoxLogin() else nav.fyersLogin() }.testTag("fyers_login_banner")) {
                Text(if (auth == LoginState.NEEDS_LOGIN) "$broker login needed for live prices. Tap here to log in." else "$broker is not set up yet. Tap to open settings.",
                    color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(12.dp))
            }
            if (feed.state != FeedState.CONNECTED) Text(feedMessage(feed.state, feed.message), color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }, bottomBar = { BottomBar(section, { section = it }, nav.account) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (section) {
                Section.HOME -> ExploreScreen(vm, nav); Section.WATCHLIST -> WatchlistsPage(vm, nav)
                Section.PORTFOLIO -> HoldingsScreen(vm, nav); Section.ORDERS -> if (ordersTab == 0) OrdersScreen(vm) else PositionsScreen(vm, nav)
            }
        }
    }
}

/** Grey strip with a rounded search box; tapping it opens HOME, where stocks are searched. */
@Composable
private fun SearchBar(onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp).testTag("header_search"), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(12.dp))
            Text("Search for Reliance, Tata", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * NIFTY and SENSEX side by side: name with the next expiry ("Expiry Tue", "Expiry Tomorrow"), then the level in the
 * day's colour with ▲/▼ and the change. Tap one to open the index.
 */
@Composable
private fun IndexCards(vm: AppVM, onIndex: (Underlying) -> Unit) {
    val quotes by vm.quotes.collectAsState(); val now by vm.now.collectAsState(); val expiries by vm.nearestExpiry.collectAsState()
    val today = now.atZone(com.papertrade.core.MarketHours.IST).toLocalDate()
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Underlying.values().forEachIndexed { i, u ->
            if (i > 0) VerticalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
            val q = quotes[u.indexSymbol]
            val fresh = freshnessOf(q, now, vm.maxAgeSec()).let { it == Freshness.LIVE || it == Freshness.DEMO }
            val c = if (fresh) Fmt.color(q?.change, MaterialTheme.colorScheme.onSurface) else MaterialTheme.colorScheme.onSurfaceVariant
            Column(Modifier.weight(1f).clickable { onIndex(u) }.padding(horizontal = 8.dp, vertical = 10.dp).testTag("ticker_${u.name}")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(u.name, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    expiries[u]?.let { e -> val soon = !e.isAfter(today.plusDays(1))
                        Text(expiryChip(e, today), fontSize = 12.sp, maxLines = 1, softWrap = false,
                            modifier = Modifier.background(if (soon) Gain.copy(alpha = 0.14f) else Accent.copy(alpha = 0.12f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Fmt.inr(q?.ltp), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c, maxLines = 1, softWrap = false)
                    q?.change?.takeIf { it.signum() != 0 }?.let { Text(if (it.signum() > 0) "▲" else "▼", fontSize = 10.sp, color = c, modifier = Modifier.padding(start = 2.dp)) }
                    Spacer(Modifier.weight(1f)); Spacer(Modifier.width(4.dp))
                    // A ~180dp half on a 360dp phone can't always hold a big SENSEX move at full size, so this one shrinks to fit.
                    FitText("${Fmt.signed(q?.change)} (${Fmt.pct(q?.changePct)})", 12.sp, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** One-line text that steps its font down (no lower than [min]) until it fits its width, instead of being cut off. */
@Composable
private fun FitText(text: String, size: androidx.compose.ui.unit.TextUnit, color: Color, min: androidx.compose.ui.unit.TextUnit = 8.sp) {
    var fs by remember(text.length, size) { mutableStateOf(size) } // keyed on length so ticking prices don't re-fit every second
    Text(text, fontSize = fs, color = color, maxLines = 1, softWrap = false, onTextLayout = { if (it.hasVisualOverflow && fs > min) fs *= 0.92f })
}

private fun expiryChip(e: java.time.LocalDate, today: java.time.LocalDate) = "Expiry " + when (val d = java.time.temporal.ChronoUnit.DAYS.between(today, e)) {
    0L -> "Today"; 1L -> "Tomorrow"
    else -> if (d in 2..6) e.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
        else e.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM", java.util.Locale.ENGLISH))
}

/** Watchlist names as tabs (selected in blue), a fixed ⊕ on the right, then the selected list. */
@Composable
private fun WatchlistsPage(vm: AppVM, nav: Nav) {
    val lists by vm.watchlists.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var newList by remember { mutableStateOf(false) }; var openNewList by remember { mutableStateOf(false) }
    LaunchedEffect(lists.size) {
        if (openNewList) { tab = lists.size - 1; openNewList = false } // jump to the list just created
        else if (tab >= lists.size) tab = 0 // its watchlist was deleted
    }
    val selected = tab.coerceIn(0, (lists.size - 1).coerceAtLeast(0)) // never index past the tabs while a deletion settles
    Column(Modifier.fillMaxSize()) {
        // Fixed height: a tab row can't report an intrinsic size, and the divider before ⊕ needs one to fill.
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            if (lists.isEmpty()) Spacer(Modifier.weight(1f))
            else ScrollableTabRow(selectedTabIndex = selected, modifier = Modifier.weight(1f), edgePadding = 12.dp, containerColor = MaterialTheme.colorScheme.surface, divider = {},
                indicator = { pos -> TabRowDefaults.SecondaryIndicator(with(TabRowDefaults) { Modifier.tabIndicatorOffset(pos[selected]) }, height = 3.dp, color = Accent) }) {
                lists.forEachIndexed { i, w ->
                    Tab(selected == i, { tab = i }, selectedContentColor = Accent, unselectedContentColor = MaterialTheme.colorScheme.onSurface,
                        text = { Text(w.name, fontSize = 17.sp, maxLines = 1, softWrap = false) }, modifier = Modifier.testTag("tab_wl_${w.id}"))
                }
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            IconButton({ newList = true }, Modifier.padding(horizontal = 8.dp).testTag("tab_add_watchlist")) { Icon(Icons.Outlined.AddCircleOutline, "New watchlist", Modifier.size(30.dp)) }
        }
        HorizontalDivider()
        lists.getOrNull(selected)?.let { WatchlistScreen(vm, nav, it, canDelete = lists.size > 1) }
    }
    if (newList) NameDialog("New watchlist", "") { name -> newList = false; name?.let { openNewList = true; vm.createWatchlist(it) } }
}

private enum class WlSort(val label: String) { DEFAULT("Default"), CHANGE_DESC("% change: high to low"), CHANGE_ASC("% change: low to high"),
    PRICE_DESC("Price: high to low"), PRICE_ASC("Price: low to high"), NAME("Name: A to Z") }
private enum class WlFilter(val label: String) { ALL("All"), CALLS("Calls"), PUTS("Puts"), FUTURES("Futures"), STOCKS("Stocks") }

/** One watchlist: a "Sort & Filter" menu (which also renames or deletes the list), then its rows. Tap a row for Buy/Sell. */
@Composable
fun WatchlistScreen(vm: AppVM, nav: Nav, list: com.papertrade.app.data.repo.Watchlist, canDelete: Boolean) {
    val quotes by vm.quotes.collectAsState(); val now by vm.now.collectAsState()
    var renaming by remember { mutableStateOf(false) }; var confirmDelete by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<WatchlistItemEntity?>(null) }
    var sort by rememberSaveable { mutableStateOf(WlSort.DEFAULT) }; var filter by rememberSaveable { mutableStateOf(WlFilter.ALL) }
    var menu by remember { mutableStateOf(false) }
    DisposableEffect(list.id, list.items.size) { vm.subscribe("watchlist", list.items.map { it.symbol }.toSet()); onDispose { vm.unsubscribe("watchlist") } }
    val shown = list.items.filter { item -> val info = vm.info(item.symbol)
        when (filter) {
            WlFilter.ALL -> true; WlFilter.CALLS -> info?.optionType == com.papertrade.core.OptionType.CE
            WlFilter.PUTS -> info?.optionType == com.papertrade.core.OptionType.PE; WlFilter.FUTURES -> info?.segment == com.papertrade.core.Segment.FUTURES
            WlFilter.STOCKS -> item.symbol.endsWith("-EQ")
        }
    }.let { l ->
        fun by(desc: Boolean, f: (WatchlistItemEntity) -> java.math.BigDecimal?) = l.sortedWith(compareBy(nullsLast(if (desc) reverseOrder() else naturalOrder()), f))
        when (sort) {
            WlSort.DEFAULT -> l; WlSort.CHANGE_DESC -> by(true) { quotes[it.symbol]?.changePct }; WlSort.CHANGE_ASC -> by(false) { quotes[it.symbol]?.changePct }
            WlSort.PRICE_DESC -> by(true) { quotes[it.symbol]?.ltp }; WlSort.PRICE_ASC -> by(false) { quotes[it.symbol]?.ltp }
            WlSort.NAME -> l.sortedBy { rowLabel(it.symbol, vm.info(it.symbol)).let { (a, b) -> "$a $b" } }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Row(Modifier.clickable { menu = true }.padding(vertical = 12.dp).testTag("sort_filter"), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sort & Filter", fontSize = 16.sp); Spacer(Modifier.width(6.dp)); Icon(Icons.Default.Tune, null, Modifier.size(18.dp))
                }
                DropdownMenu(menu, { menu = false }) {
                    MenuHeader("Sort by")
                    WlSort.values().forEach { s -> DropdownMenuItem({ Text(s.label, fontWeight = if (s == sort) FontWeight.SemiBold else FontWeight.Normal) }, { sort = s; menu = false },
                        trailingIcon = { if (s == sort) Icon(Icons.Default.Check, null, tint = Accent) }) }
                    HorizontalDivider(); MenuHeader("Show")
                    WlFilter.values().forEach { f -> DropdownMenuItem({ Text(f.label, fontWeight = if (f == filter) FontWeight.SemiBold else FontWeight.Normal) }, { filter = f; menu = false },
                        trailingIcon = { if (f == filter) Icon(Icons.Default.Check, null, tint = Accent) }) }
                    HorizontalDivider()
                    DropdownMenuItem({ Text("Rename watchlist") }, { menu = false; renaming = true }, Modifier.testTag("rename_watchlist"), leadingIcon = { Icon(Icons.Default.Edit, null) })
                    if (canDelete) DropdownMenuItem({ Text("Delete watchlist") }, { menu = false; confirmDelete = true }, Modifier.testTag("delete_watchlist"), leadingIcon = { Icon(Icons.Default.DeleteOutline, null) })
                }
            }
            Spacer(Modifier.weight(1f))
            if (sort != WlSort.DEFAULT || filter != WlFilter.ALL) Text(listOfNotNull(filter.takeIf { it != WlFilter.ALL }?.label, sort.takeIf { it != WlSort.DEFAULT }?.label).joinToString(" · "),
                fontSize = 12.sp, color = Accent, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        HorizontalDivider()
        when {
            list.items.isEmpty() -> EmptyState(Icons.Default.StarBorder, "This watchlist is empty", "Search in HOME and tap ☆ to add a stock.")
            shown.isEmpty() -> Text("No ${filter.label.lowercase()} in this watchlist", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            else -> LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
                items(shown, key = { it.symbol }) { item -> Box(Modifier.testTag("wl_item_${item.symbol}")) {
                    WatchRow(rowLabel(item.symbol, vm.info(item.symbol)), quotes[item.symbol], freshnessOf(quotes[item.symbol], now, vm.maxAgeSec())) { action = item } }
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

@Composable
private fun MenuHeader(text: String) = Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))

/** Row label: ("NIFTY", "03-NOV-26 23350 PE • NSE FO"), ("NIFTY", "24-NOV-26 FUT • NSE FO"), or ("RELIANCE", "NSE") for stocks. */
private fun rowLabel(symbol: String, info: com.papertrade.app.data.market.InstrumentInfo?): Pair<String, String> {
    // Demo symbols are "DEMO:…"; show the exchange the real contract trades on.
    val exchange = symbol.substringBefore(':').takeIf { it != "DEMO" } ?: if (info?.underlying == "SENSEX") "BSE" else "NSE"
    val e = info?.expiry ?: return symbol.substringAfter(':').removeSuffix("-EQ") to exchange
    val what = info.optionType?.let { "${info.strike?.stripTrailingZeros()?.toPlainString() ?: ""} ${it.name}" } ?: "FUT"
    return (info.underlying ?: symbol.substringAfter(':')) to "${e.format(rowDate).uppercase()} $what • $exchange FO"
}
private val rowDate = java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yy", java.util.Locale.ENGLISH)

/** Watchlist row: name over contract details on the left; price in the day's colour over "+₹32.55 (+5.22%)" on the right. */
@Composable
private fun WatchRow(label: Pair<String, String>, q: MarketQuote?, f: Freshness, onClick: () -> Unit) {
    val fresh = f == Freshness.LIVE || f == Freshness.DEMO; val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label.first, fontSize = 17.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(label.second, fontSize = 12.sp, color = dim, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.rupee(q?.ltp), fontSize = 17.sp, color = if (fresh) Fmt.color(q?.change, MaterialTheme.colorScheme.onSurface) else dim, maxLines = 1, softWrap = false)
                Text(q?.change?.let { c -> "${if (c.signum() < 0) "-" else "+"}₹${Fmt.inr(c.abs())} (${Fmt.pct(q.changePct)})" } ?: "—", fontSize = 12.sp, color = dim,
                    maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 6.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** "Orders  Positions" as large text tabs in the ORDERS section, where the search bar sits elsewhere: selected dark, other grey. */
@Composable
private fun OrdersTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        listOf("Orders", "Positions").forEachIndexed { i, t ->
            Text(t, fontSize = 22.sp, fontWeight = if (selected == i) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected == i) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSelect(i) }.padding(horizontal = 8.dp, vertical = 8.dp).testTag("tab_$t"))
        }
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

/** HOME, WATCHLIST, PORTFOLIO and ORDERS switch the main page; ACCOUNT opens settings. The selected item is blue, with no pill. */
@Composable
private fun BottomBar(section: Section, onSection: (Section) -> Unit, onAccount: () -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        val colors = NavigationBarItemDefaults.colors(selectedIconColor = Accent, selectedTextColor = Accent, indicatorColor = Color.Transparent,
            unselectedIconColor = MaterialTheme.colorScheme.onSurface, unselectedTextColor = MaterialTheme.colorScheme.onSurface)
        listOf(Triple(Section.HOME, Icons.Outlined.Home, Icons.Filled.Home), Triple(Section.WATCHLIST, Icons.Outlined.StarBorder, Icons.Filled.Stars),
            Triple(Section.PORTFOLIO, Icons.Outlined.Folder, Icons.Filled.Folder), Triple(Section.ORDERS, Icons.Outlined.ContentPaste, Icons.Filled.ContentPaste))
            .forEach { (s, icon, selectedIcon) ->
                NavigationBarItem(selected = section == s, onClick = { onSection(s) }, icon = { Icon(if (section == s) selectedIcon else icon, null) },
                    label = { Text(s.name, fontSize = 10.sp, maxLines = 1, softWrap = false) }, colors = colors, modifier = Modifier.testTag("bottom_${s.name}"))
            }
        NavigationBarItem(selected = false, onClick = onAccount, icon = { Icon(Icons.Outlined.AccountBalanceWallet, null) },
            label = { Text("ACCOUNT", fontSize = 10.sp, maxLines = 1, softWrap = false) }, colors = colors, modifier = Modifier.testTag("bottom_ACCOUNT"))
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
        if (results == null) item { Text("Search for a stock and tap ☆ to add it to a watchlist.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
