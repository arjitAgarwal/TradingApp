package com.papertrade.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.AddCircleOutline
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
            if (section == Section.ORDERS) OrdersTabs(ordersTab, { ordersTab = it }, onSearch = { section = Section.HOME }, onMenu = nav.account) else SearchBar { section = Section.HOME }
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
    Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)).padding(horizontal = 17.dp, vertical = 13.dp)) {
        Row(Modifier.fillMaxWidth().height(39.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp).testTag("header_search"), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(12.dp))
            Text("Search for Reliance, Tata", fontSize = 14.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            val c = Fmt.color(q?.change, MaterialTheme.colorScheme.onSurface) // by direction even when the market is shut
            // 8 dp from the screen edge, 12 dp after the divider; 53 dp tall in all.
            Column(Modifier.weight(1f).clickable { onIndex(u) }.padding(start = if (i == 0) 8.dp else 12.dp, end = if (i == 0) 12.dp else 6.dp, top = 8.dp, bottom = 6.dp).testTag("ticker_${u.name}")) {
                Row(Modifier.height(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(u.name, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f))
                    expiries[u]?.let { e -> val soon = !e.isAfter(today.plusDays(1))
                        Box(Modifier.fillMaxHeight().background(if (soon) Gain.copy(alpha = 0.14f) else Accent.copy(alpha = 0.12f), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
                            Text(expiryChip(e, today), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, softWrap = false)
                        }
                    }
                }
                Row(Modifier.padding(top = 2.dp).height(19.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Fmt.inr(q?.ltp), fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, color = c, maxLines = 1, softWrap = false)
                    q?.change?.takeIf { it.signum() != 0 }?.let { Text(if (it.signum() > 0) "▲" else "▼", fontSize = 10.sp, lineHeight = 12.sp, color = c, modifier = Modifier.padding(start = 3.dp)) }
                    Spacer(Modifier.weight(1f)); Spacer(Modifier.width(4.dp))
                    // A ~180dp half on a 360dp phone can't always hold a big SENSEX move at full size, so this one shrinks to fit.
                    FitText("${Fmt.signed(q?.change)} (${Fmt.pct(q?.changePct)})", 12.sp, MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** One-line text that steps its font down (no lower than [min]) until it fits its width, instead of being cut off. */
@Composable
private fun FitText(text: String, size: androidx.compose.ui.unit.TextUnit, color: Color, min: androidx.compose.ui.unit.TextUnit = 8.sp) {
    var fs by remember(text.length, size) { mutableStateOf(size) } // keyed on length so ticking prices don't re-fit every second
    Text(text, fontSize = fs, lineHeight = fs * 1.25f, color = color, maxLines = 1, softWrap = false, onTextLayout = { if (it.hasVisualOverflow && fs > min) fs *= 0.92f })
}

private fun expiryChip(e: java.time.LocalDate, today: java.time.LocalDate) = "Expiry " + when (val d = java.time.temporal.ChronoUnit.DAYS.between(today, e)) {
    0L -> "Today"; 1L -> "Tomorrow"
    else -> if (d in 2..6) e.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH)
        else e.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM", java.util.Locale.ENGLISH))
}

/** A fixed "Options" tab, then the watchlist names as tabs (selected in blue), a fixed ⊕ on the right, then the selected page. */
@Composable
private fun WatchlistsPage(vm: AppVM, nav: Nav) {
    val lists by vm.watchlists.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) } // 0 is Options; watchlist i is tab i + 1
    var newList by remember { mutableStateOf(false) }; var openNewList by remember { mutableStateOf(false) }
    LaunchedEffect(lists.size) {
        if (openNewList) { tab = lists.size; openNewList = false } // jump to the list just created
        else if (tab > lists.size) tab = 0 // its watchlist was deleted
    }
    val selected = tab.coerceIn(0, lists.size) // never index past the tabs while a deletion settles
    val tabs = listOf("Options" to "tab_options") + lists.map { it.name to "tab_wl_${it.id}" }
    Column(Modifier.fillMaxSize()) {
        // Fixed height: a tab row can't report an intrinsic size, and the divider before ⊕ needs one to fill.
        // The underline is as wide as the name, not the tab; names start 14 dp in, 22 dp apart.
        Row(Modifier.fillMaxWidth().height(49.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()).padding(start = 3.dp)) {
                tabs.forEachIndexed { i, (name, tag) ->
                    Column(Modifier.fillMaxHeight().clickable { tab = i }.padding(horizontal = 11.dp).width(IntrinsicSize.Max).testTag(tag)) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(name, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, softWrap = false, color = if (selected == i) Accent else MaterialTheme.colorScheme.onSurface)
                        }
                        Box(Modifier.fillMaxWidth().height(3.dp).background(if (selected == i) Accent else Color.Transparent, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)))
                    }
                }
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.width(49.dp).fillMaxHeight().clickable { newList = true }.testTag("tab_add_watchlist"), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.AddCircleOutline, "New watchlist", Modifier.size(25.dp))
            }
        }
        HorizontalDivider()
        if (selected == 0) OptionsTab(vm, nav) else lists.getOrNull(selected - 1)?.let { WatchlistScreen(vm, nav, it, canDelete = lists.size > 1) }
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
                Row(Modifier.clickable { menu = true }.height(28.dp).testTag("sort_filter"), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sort & Filter", fontSize = 12.sp, lineHeight = 16.sp); Spacer(Modifier.width(6.dp)); Icon(Icons.Default.Tune, null, Modifier.size(15.dp))
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
                    WatchRow(rowLabel(item.symbol, vm.info(item.symbol)), quotes[item.symbol]) { action = item } }
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
private fun WatchRow(label: Pair<String, String>, q: MarketQuote?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        // 62 dp a row: name centred 20 dp down, the contract line 45 dp down.
        Row(Modifier.padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label.first, fontSize = 16.sp, lineHeight = 20.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(label.second, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.5.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.rupee(q?.ltp), fontSize = 16.sp, lineHeight = 20.sp, color = Fmt.color(q?.change, MaterialTheme.colorScheme.onSurface), maxLines = 1, softWrap = false)
                Text(q?.change?.let { c -> "${if (c.signum() < 0) "-" else "+"}₹${Fmt.inr(c.abs())} (${Fmt.pct(q.changePct)})" } ?: "—", fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 7.5.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** "Orders  Positions" as text tabs in the ORDERS section (selected near-black, the other light slate), with search and ⋮ on the right. */
@Composable
private fun OrdersTabs(selected: Int, onSelect: (Int) -> Unit, onSearch: () -> Unit, onMenu: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 9.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf("Orders", "Positions").forEachIndexed { i, t ->
            Text(t, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = if (selected == i) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected == i) Color(0xFF1A1D24) else Color(0xFF8A93A6),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSelect(i) }.padding(horizontal = 7.dp, vertical = 6.dp).testTag("tab_$t"))
        }
        Spacer(Modifier.weight(1f))
        IconButton(onSearch, Modifier.testTag("orders_search")) { Icon(Icons.Default.Search, "Search", Modifier.size(26.dp), tint = Color(0xFF1F2430)) }
        IconButton(onMenu, Modifier.testTag("orders_menu")) { Icon(Icons.Default.MoreVert, "More", tint = Color(0xFF1F2430)) }
    }
}

/** Groww-style sheet shown on tapping a watchlist row: name card (tap to open full details), Set alert, Remove, Sell / Buy. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickActionSheet(vm: AppVM, symbol: String, q: MarketQuote?, onClose: () -> Unit, onOpen: () -> Unit, onTrade: (Side) -> Unit, onRemove: (() -> Unit)?) {
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
            // Rows have no star (as in Groww), so removal lives here. Options-tab strikes aren't in a list, so they have none.
            if (onRemove != null) {
                Row(Modifier.fillMaxWidth().clickable(onClick = onRemove).padding(vertical = 16.dp, horizontal = 4.dp).testTag("quick_remove"), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DeleteOutline, null); Spacer(Modifier.width(16.dp)); Text("Remove from watchlist", fontSize = 16.sp)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
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
    Column(Modifier.background(MaterialTheme.colorScheme.surface).navigationBarsPadding()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // 68 dp: icon centred 19 dp down, label centred 49 dp down.
        Row(Modifier.fillMaxWidth().height(68.dp)) {
            // The selected item swaps to its solid blue icon.
            listOf(Triple(Section.HOME, NavIcons.home, NavIcons.homeSelected), Triple(Section.WATCHLIST, NavIcons.star, NavIcons.starSelected),
                Triple(Section.PORTFOLIO, NavIcons.folder, NavIcons.folderSelected), Triple(Section.ORDERS, NavIcons.clipboard, NavIcons.clipboardSelected))
                .forEach { (s, icon, solid) -> val sel = section == s
                    BottomItem(s.name, if (sel) solid else icon, sel, "bottom_${s.name}") { onSection(s) }
                }
            BottomItem("ACCOUNT", NavIcons.wallet, false, "bottom_ACCOUNT", onAccount)
        }
    }
}

@Composable
private fun RowScope.BottomItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, tag: String, onClick: () -> Unit) {
    val c = if (selected) Accent else MaterialTheme.colorScheme.onSurface
    Column(Modifier.weight(1f).fillMaxHeight().clickable(onClick = onClick).padding(top = 6.dp).testTag(tag), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(26.dp), tint = if (selected) Color.Unspecified else c); Spacer(Modifier.height(11.dp))
        Text(label, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, color = c, maxLines = 1, softWrap = false)
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


/**
 * The fixed first WATCHLIST tab: Call/Put, the index chips (the selected one carries its expiry picker), then that
 * expiry's strikes around the money, each tagged ITM/ATM/OTM. Tap a strike for Buy/Sell.
 */
@Composable
private fun OptionsTab(vm: AppVM, nav: Nav) {
    var calls by rememberSaveable { mutableStateOf(true) }; var u by rememberSaveable { mutableStateOf(Underlying.NIFTY) }
    var expiry by remember(u) { mutableStateOf<java.time.LocalDate?>(null) } // null: the nearest one
    var chain by remember(u) { mutableStateOf<com.papertrade.app.data.market.OptionChain?>(null) }; var error by remember(u) { mutableStateOf<String?>(null) }
    var action by remember { mutableStateOf<String?>(null) }
    val quotes by vm.quotes.collectAsState(); val provider by vm.provider.collectAsState()
    LaunchedEffect(u, expiry, provider) {
        // One chain read gives the strikes; their prices then tick through the quote feed. Re-read each minute so the
        // money line follows the index (1 request a minute, well inside the FYERS limits), and stop once a live market shuts.
        while (true) {
            vm.optionChain(u, expiry).fold({ chain = it; error = null }, { error = it.message })
            if (chain?.source != com.papertrade.core.QuoteSource.DEMO && !com.papertrade.core.MarketHours.isLikelyOpen(java.time.Instant.now())) break
            kotlinx.coroutines.delay(60_000)
        }
    }
    val ch = chain
    val sides = ch?.rows.orEmpty().mapNotNull { r -> (if (calls) r.call else r.put)?.let { r.strike to it } }
    val symbols = sides.map { it.second.symbol }
    DisposableEffect(symbols) { vm.subscribe("options", symbols.toSet()); onDispose { vm.unsubscribe("options") } }
    val spot = quotes[u.indexSymbol]?.ltp ?: ch?.underlyingLtp
    val atm = spot?.let { s -> sides.minByOrNull { (it.first - s).abs() }?.first }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Open with the money line in view, a few strikes from the top.
    LaunchedEffect(u, ch?.selectedExpiry, calls, atm != null) { sides.indexOfFirst { it.first == atm }.takeIf { it >= 0 }?.let { listState.scrollToItem((it - 4).coerceAtLeast(0)) } }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(46.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CallPut(calls) { calls = it }
                VerticalDivider(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Underlying.values().forEach { x ->
                    if (x == u) ExpiryChip(x.name, ch?.selectedExpiry, ch?.expiries.orEmpty()) { expiry = it }
                    else Box(Modifier.height(32.dp).clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).clickable { u = x }.padding(horizontal = 18.dp).testTag("opt_u_${x.name}"),
                        contentAlignment = Alignment.Center) { Text(x.name, fontSize = 14.sp, lineHeight = 18.sp) }
                    Spacer(Modifier.width(8.dp))
                }
            }
            when {
                ch == null && error != null -> Text("Option chain unavailable: $error", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                ch == null -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> LazyColumn(Modifier.testTag("options_list"), state = listState, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 60.dp)) {
                    items(sides, key = { it.second.symbol }) { (strike, s) ->
                        val money = when { atm == null -> null; strike == atm -> "ATM"; (strike < atm) == calls -> "ITM"; else -> "OTM" }
                        StrikeRow(u, strike, if (calls) "CE" else "PE", ch.selectedExpiry, money, quotes[s.symbol] ?: s.quote) { action = s.symbol }
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 15.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(Icons.Default.CandlestickChart, "Charts", Modifier.weight(1f).testTag("opt_charts")) { nav.index(u) }
            PillButton(Icons.Default.Link, "Option Chain", Modifier.weight(1f).testTag("opt_chain")) { nav.chain(u) }
        }
    }
    action?.let { sym -> QuickActionSheet(vm, sym, quotes[sym], { action = null }, onOpen = { action = null; nav.contract(sym) },
        onTrade = { side -> action = null; nav.order(sym, side) }, onRemove = null) }
}

/** "Call | Put" in one outlined pill; the selected half is filled blue. */
@Composable
private fun CallPut(calls: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.height(32.dp).clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).padding(2.dp)) {
        listOf(true to "Call", false to "Put").forEach { (v, t) ->
            Box(Modifier.fillMaxHeight().clip(CircleShape).background(if (calls == v) Accent else Color.Transparent).clickable { onChange(v) }.padding(horizontal = 12.dp).testTag("opt_$t"),
                contentAlignment = Alignment.Center) { Text(t, fontSize = 14.sp, lineHeight = 18.sp, color = if (calls == v) Color.White else MaterialTheme.colorScheme.onSurface) }
        }
    }
}

/** The selected index: its name in a blue pill, then "03 Nov ⌄", which opens the list of expiries. */
@Composable
private fun ExpiryChip(name: String, selected: java.time.LocalDate?, expiries: List<java.time.LocalDate>, onPick: (java.time.LocalDate) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.height(32.dp).clip(CircleShape).border(1.dp, Accent.copy(alpha = 0.35f), CircleShape), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.fillMaxHeight().border(1.dp, Accent, CircleShape).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
            Text(name, fontSize = 14.sp, lineHeight = 18.sp, color = Accent)
        }
        Box {
            Row(Modifier.fillMaxHeight().clickable(enabled = expiries.isNotEmpty()) { open = true }.padding(start = 14.dp, end = 10.dp).testTag("opt_expiry"), verticalAlignment = Alignment.CenterVertically) {
                Text(selected?.format(chipDate) ?: "—", fontSize = 14.sp, lineHeight = 18.sp); Spacer(Modifier.width(6.dp))
                Icon(Icons.Default.KeyboardArrowDown, "Change expiry", Modifier.size(18.dp), tint = Accent)
            }
            DropdownMenu(open, { open = false }) {
                expiries.forEach { e -> DropdownMenuItem({ Text(e.format(chipDate), fontWeight = if (e == selected) FontWeight.SemiBold else FontWeight.Normal) }, { open = false; onPick(e) },
                    trailingIcon = { if (e == selected) Icon(Icons.Default.Check, null, tint = Accent) }) }
            }
        }
    }
}
private val chipDate = java.time.format.DateTimeFormatter.ofPattern("dd MMM", java.util.Locale.ENGLISH)
private val strikeDate = java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", java.util.Locale.ENGLISH)

/** One strike, laid out like a watchlist row: "NIFTY [ITM]" over "03 Nov 2026 **22850** CE"; price with ▲/▼ over "₹19.25 (+9.12%)". */
@Composable
private fun StrikeRow(u: Underlying, strike: java.math.BigDecimal, type: String, expiry: java.time.LocalDate, money: String?, q: MarketQuote?, onClick: () -> Unit) {
    val c = Fmt.color(q?.change, MaterialTheme.colorScheme.onSurface)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("opt_row_$strike")) {
        Row(Modifier.padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(Modifier.height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(u.name, fontSize = 16.sp, lineHeight = 20.sp); money?.let { Spacer(Modifier.width(6.dp)); MoneyTag(it) }
                }
                Text(androidx.compose.ui.text.buildAnnotatedString {
                    append(expiry.format(strikeDate) + " ")
                    pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.SemiBold)); append(strike.stripTrailingZeros().toPlainString()); pop()
                    append(" $type")
                }, fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 7.5.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Row(Modifier.height(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(Fmt.rupee(q?.ltp), fontSize = 16.sp, lineHeight = 20.sp, color = c, maxLines = 1, softWrap = false)
                    q?.change?.takeIf { it.signum() != 0 }?.let { Text(if (it.signum() > 0) "▲" else "▼", fontSize = 10.sp, lineHeight = 12.sp, color = c, modifier = Modifier.padding(start = 3.dp)) }
                }
                Text(q?.change?.let { d -> "${if (d.signum() < 0) "-" else ""}₹${Fmt.inr(d.abs())} (${Fmt.pct(q.changePct)})" } ?: "—", fontSize = 12.sp, lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 7.5.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** ITM green, ATM amber, OTM grey. */
@Composable
private fun MoneyTag(t: String) {
    val (bg, fg) = when (t) { "ITM" -> Gain.copy(alpha = 0.12f) to Color(0xFF0B7A5C); "ATM" -> Color(0xFFFFEBD2) to Color(0xFF9A5B00); else -> Color(0xFFEDEEF3) to Color(0xFF55586A) }
    Box(Modifier.height(16.dp).background(bg, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
        Text(t, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

/** Light blue pill button with a blue icon and label, as under the Options list. */
@Composable
private fun PillButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Row(modifier.height(38.dp).clip(CircleShape).background(Color(0xFFEEF0FA)).clickable(onClick = onClick), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = Accent); Spacer(Modifier.width(8.dp)); Text(label, fontSize = 15.sp, lineHeight = 20.sp, color = Accent)
    }
}
