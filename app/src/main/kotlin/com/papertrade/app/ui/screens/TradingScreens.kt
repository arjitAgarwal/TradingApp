package com.papertrade.app.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.CandlestickChart
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import com.papertrade.app.ui.theme.Accent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papertrade.app.data.market.DataMode
import com.papertrade.app.data.market.LiveSource
import com.papertrade.app.data.repo.AppSettings
import com.papertrade.app.data.repo.LoginState
import com.papertrade.app.ui.AppVM
import com.papertrade.app.ui.Fmt
import com.papertrade.app.ui.components.*
import com.papertrade.app.ui.theme.Gain
import com.papertrade.app.ui.theme.Loss
import com.papertrade.app.ui.theme.Warn
import com.papertrade.core.*
import java.math.BigDecimal

private fun name(vm: AppVM, id: String) = vm.info(id)?.let { it.name.takeIf { n -> it.segment != Segment.EQUITY } ?: id.substringAfter(':').removeSuffix("-EQ") } ?: id

@Composable
fun OrdersScreen(vm: AppVM) {
    val acc by vm.account.collectAsState(); var showAll by rememberSaveable { mutableStateOf(false) }; var sel by remember { mutableStateOf<Order?>(null) }
    val all = (acc?.orders?.values ?: emptyList()).sortedByDescending { it.createdAt }
    val open = all.filter { it.status.isActive || it.status == OrderStatus.CREATED }
    // "Every trade made": anything that got at least one fill. Cancelled/rejected orders only appear under "All orders".
    val traded = all.filter { it !in open && it.filledQuantity > 0 }
    val shown = if (showAll) all else open + traded
    if (shown.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { EmptyState(Icons.Outlined.Inventory2, "You have no orders", "Trades you make show up here.") }
            if (all.isNotEmpty()) TextButton({ showAll = true }, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 24.dp).testTag("all_orders")) { Text("All orders") }
        }
    } else LazyColumn(Modifier.fillMaxSize()) {
        if (showAll) {
            item { SectionHeader("All orders") { TextButton({ showAll = false }) { Text("Back to trades") } } }
            items(all, key = { it.id }) { o -> OrderRow(vm, o) { sel = o } }
        } else {
            if (open.isNotEmpty()) { item { SectionHeader("Open (${open.size})") }; items(open, key = { it.id }) { o -> OrderRow(vm, o) { sel = o } } }
            if (traded.isNotEmpty()) { item { SectionHeader("Executed (${traded.size})") }; items(traded, key = { it.id }) { o -> OrderRow(vm, o) { sel = o } } }
            if (all.size > shown.size) item { TextButton({ showAll = true }, Modifier.padding(horizontal = 8.dp).testTag("all_orders")) { Text("All orders") } }
        }
    }
    sel?.let { o0 -> val o = acc?.orders?.get(o0.id) ?: o0; val fills = acc?.fills?.filter { it.orderId == o.id } ?: emptyList()
        AlertDialog(onDismissRequest = { sel = null }, title = { Text("Order ${o.id}") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            StatRow("Instrument", o.instrumentId); StatRow("Side / type", "${o.side} ${o.type}"); StatRow("Quantity", "${o.filledQuantity} of ${o.quantity}"); StatRow("Limit price", Fmt.inr(o.limitPrice))
            StatRow("Average fill", Fmt.inr(o.avgFillPrice)); StatRow("Status", o.status.name); StatRow("Placed", Fmt.time(o.createdAt)); StatRow("Executed", Fmt.time(o.executedAt))
            fills.forEach { StatRow("Fill ${it.quantity} @ ${Fmt.inr(it.price)}", "charges ${Fmt.rupee(it.charges)}") }
            o.rejectReason?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
            confirmButton = { if (o.status.isActive || o.status == OrderStatus.CREATED) TextButton({ vm.cancel(o.id); sel = null }, modifier = Modifier.testTag("cancel_order")) { Text("Cancel order") } else TextButton({ sel = null }) { Text("Close") } },
            dismissButton = { if (o.status.isActive) TextButton({ sel = null }) { Text("Close") } })
    }
}

@Composable
private fun SectionHeader(title: String, trailing: @Composable () -> Unit = {}) =
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)); trailing()
    }

/** Flat row: name and "BUY · 65 qty · 18 Oct, 10:42" on the left; price and status on the right. */
@Composable
private fun OrderRow(vm: AppVM, o: Order, onClick: () -> Unit) {
    val (status, statusColor) = when (o.status) {
        OrderStatus.FILLED -> "Executed" to Gain; OrderStatus.PARTIALLY_FILLED -> "Partly executed" to Warn
        OrderStatus.OPEN, OrderStatus.CREATED -> "Open" to Warn; OrderStatus.CANCELLED -> "Cancelled" to MaterialTheme.colorScheme.onSurfaceVariant
        OrderStatus.REJECTED -> "Rejected" to Loss
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("order_${o.id}")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name(vm, o.instrumentId), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(o.side.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (o.side == Side.BUY) Gain else Loss)
                    Text(" · ${if (o.filledQuantity > 0) o.filledQuantity else o.quantity} qty · ${Fmt.time(o.executedAt ?: o.createdAt).removeSuffix(" IST")}",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.rupee(o.avgFillPrice ?: o.limitPrice), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(status, fontSize = 12.sp, color = statusColor)
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
    }
}

private enum class PosFilter(val label: String) { ALL("All positions"), OPEN("Open"), CLOSED("Closed") }

/**
 * Today's positions: a toolbar (filter, search, Secure Exit, Exit all), one card per F&O position with One Tap Exit,
 * closed positions greyed below with today's buy and sell averages, and a Total bar pinned to the bottom (tap it for
 * realised/unrealised). Tapping a card opens the same quote sheet as a watchlist row.
 */
@Composable
fun PositionsScreen(vm: AppVM, nav: Nav) {
    val acc by vm.account.collectAsState(); val val_ by vm.valuation.collectAsState(); val quotes by vm.quotes.collectAsState()
    val now by vm.now.collectAsState(); val today = now.atZone(MarketHours.IST).toLocalDate()
    // Last fill per instrument, to know which day a closed position was booked.
    val lastFill = acc?.fills?.groupBy { it.instrumentId }?.mapValues { (_, f) -> f.maxOf { it.time }.atZone(MarketHours.IST).toLocalDate() } ?: emptyMap()
    // Only today's book: expired contracts drop off, and closed positions show only on the day they were closed.
    val all = acc?.positions?.values?.filter { p -> vm.info(p.instrumentId)?.segment != Segment.EQUITY && !p.instrumentId.endsWith("-EQ") }
        ?.filter { p -> vm.info(p.instrumentId)?.expiry?.let { !it.isBefore(today) } ?: true }
        ?.filter { p -> p.isOpen || lastFill[p.instrumentId] == today } ?: emptyList()
    var sel by remember { mutableStateOf<Position?>(null) }; var confirmExitAll by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(PosFilter.ALL) }; var filterMenu by remember { mutableStateOf(false) }
    var searching by rememberSaveable { mutableStateOf(false) }; var query by rememberSaveable { mutableStateOf("") }
    var breakdown by rememberSaveable { mutableStateOf(false) }
    if (all.isEmpty()) { EmptyState(Icons.Outlined.Insights, "No positions", "Futures and options you buy show up here."); return }
    // A position row keeps its realised P&L and charges across days; today's book leaves out what earlier days booked,
    // so re-entering a contract closed yesterday doesn't bring back yesterday's profit.
    val earlier = acc?.fills?.filter { it.time.atZone(MarketHours.IST).toLocalDate().isBefore(today) }?.groupBy { it.instrumentId }
        ?.mapValues { (_, f) -> f.fold(BigDecimal.ZERO) { s, x -> s + x.realizedGross } to f.fold(BigDecimal.ZERO) { s, x -> s + x.charges } } ?: emptyMap()
    val pnl = all.associate { p -> p.instrumentId to ((val_?.positions?.firstOrNull { it.instrumentId == p.instrumentId }?.netPnl ?: p.realizedNet)
        - (earlier[p.instrumentId]?.let { (g, c) -> g - c } ?: BigDecimal.ZERO)) }
    // Today's fills per contract: lots traded and the average buy and sell prices on the closed cards.
    val todays = acc?.fills?.filter { it.time.atZone(MarketHours.IST).toLocalDate() == today }?.groupBy { it.instrumentId } ?: emptyMap()
    fun avg(id: String, side: Side) = todays[id]?.filter { it.side == side }?.takeIf { it.isNotEmpty() }
        ?.let { f -> Dec.div(f.fold(BigDecimal.ZERO) { s, x -> s + x.price * BigDecimal(x.quantity) }, BigDecimal(f.sumOf { it.quantity })) }
    fun lots(id: String) = todays[id].orEmpty().let { f -> maxOf(f.filter { it.side == Side.BUY }.sumOf { it.quantity }, f.filter { it.side == Side.SELL }.sumOf { it.quantity }) }
    val open = all.filter { it.isOpen }; val closed = all.filterNot { it.isOpen }
    // Open positions are always streamed; closed ones are not, so keep their LTP live while they are listed here.
    val closedSyms = closed.map { it.instrumentId }.toSet()
    DisposableEffect(closedSyms) { vm.subscribe("positions_closed", closedSyms); onDispose { vm.unsubscribe("positions_closed") } }
    fun matches(p: Position) = query.isBlank() || posTitle(p.instrumentId, vm.info(p.instrumentId)).text.contains(query.trim(), ignoreCase = true)
    val shownOpen = (if (filter == PosFilter.CLOSED) emptyList() else open).filter(::matches)
    val shownClosed = (if (filter == PosFilter.OPEN) emptyList() else closed).filter(::matches)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton({ filterMenu = true }, Modifier.testTag("pos_filter")) { Icon(NavIcons.sliders, "Filter positions", Modifier.size(20.dp), tint = ExitBlue) }
                DropdownMenu(filterMenu, { filterMenu = false }) {
                    PosFilter.values().forEach { f -> DropdownMenuItem({ Text(f.label, fontWeight = if (f == filter) FontWeight.SemiBold else FontWeight.Normal) }, { filter = f; filterMenu = false }) }
                }
            }
            IconButton({ searching = !searching; if (!searching) query = "" }, Modifier.testTag("pos_search")) { Icon(NavIcons.search, "Search positions", Modifier.size(24.dp), tint = ExitBlue) }
            if (filter != PosFilter.ALL) Text(filter.label, fontSize = 12.sp, color = dim, maxLines = 1)
            Spacer(Modifier.weight(1f))
            // Secure Exit (auto-exit at a total profit/loss limit) isn't built yet.
            TextButton({ vm.messages.tryEmit("Secure Exit is coming soon") }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                Icon(NavIcons.secureExit, null, Modifier.size(21.dp), tint = ExitBlue); Spacer(Modifier.width(7.dp)); Text("SECURE EXIT", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ExitBlue)
            }
            Spacer(Modifier.width(6.dp))
            TextButton({ confirmExitAll = true }, Modifier.testTag("exit_all"), enabled = open.isNotEmpty(), contentPadding = PaddingValues(horizontal = 6.dp)) {
                Icon(NavIcons.exitAll, null, Modifier.size(21.dp), tint = if (open.isNotEmpty()) ExitBlue else dim); Spacer(Modifier.width(7.dp))
                Text("EXIT ALL", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (open.isNotEmpty()) ExitBlue else dim)
            }
        }
        if (searching) OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search positions") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("pos_query"))
        LazyColumn(Modifier.weight(1f)) {
            items(shownOpen, key = { it.instrumentId }) { p ->
                PositionCard(vm, p, pnl.getValue(p.instrumentId), quotes[p.instrumentId], null, onExit = { vm.closePosition(p) }) { sel = p }
            }
            items(shownClosed, key = { it.instrumentId }) { p ->
                PositionCard(vm, p, pnl.getValue(p.instrumentId), quotes[p.instrumentId], Triple(lots(p.instrumentId), avg(p.instrumentId, Side.BUY), avg(p.instrumentId, Side.SELL)), onExit = {}) { sel = p }
            }
            if (shownOpen.isEmpty() && shownClosed.isEmpty()) item { Text("No positions match", color = dim, modifier = Modifier.padding(16.dp)) }
        }
        // Total of today's book (all visible rows, whatever the filter), pinned above the bottom bar.
        val total = pnl.values.fold(BigDecimal.ZERO, BigDecimal::add)
        val unrealised = open.mapNotNull { p -> val_?.positions?.firstOrNull { it.instrumentId == p.instrumentId }?.unrealized }.fold(BigDecimal.ZERO, BigDecimal::add)
        Column(Modifier.fillMaxWidth().background(Fmt.color(total, dim).copy(alpha = 0.14f)).clickable { breakdown = !breakdown }.padding(horizontal = 16.dp).testTag("positions_total_bar")) {
            if (breakdown) { Spacer(Modifier.height(8.dp)); StatRow("Realised today", Fmt.signed(total - unrealised)); StatRow("Unrealised", Fmt.signed(unrealised)) }
            Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp), tint = TotalCheck); Spacer(Modifier.width(8.dp))
                Text("Total", fontSize = 15.sp, lineHeight = 20.sp, color = CardInk, modifier = Modifier.weight(1f))
                Text(signedRupee(total), fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = CardInk, modifier = Modifier.testTag("positions_total")) // the bar's tint shows profit or loss
                Spacer(Modifier.width(6.dp))
                Icon(if (breakdown) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, if (breakdown) "Hide breakdown" else "Show breakdown", Modifier.size(20.dp), tint = CardInk)
            }
        }
    }
    sel?.let { p -> val sym = p.instrumentId; val lists by vm.watchlists.collectAsState(); val holder = lists.firstOrNull { w -> w.items.any { it.symbol == sym } }
        DisposableEffect(sym) { vm.subscribe("position_sheet", setOf(sym)); onDispose { vm.unsubscribe("position_sheet") } } // a closed position isn't otherwise streamed
        QuickActionSheet(vm, nav, sym, quotes[sym], inList = holder != null, onClose = { sel = null },
            onOpen = { sel = null; if (sym.endsWith("-EQ")) nav.stock(sym) else nav.contract(sym) },
            onTrade = { side -> sel = null; nav.order(sym, side) },
            onStar = { if (holder != null) vm.removeFromWatchlist(holder.id, sym) else lists.firstOrNull()?.let { w -> vm.info(sym)?.let(vm::register); vm.addToWatchlist(w.id, sym, vm.info(sym)?.name ?: sym) } })
    }
    if (confirmExitAll) AlertDialog(onDismissRequest = { confirmExitAll = false }, title = { Text("Exit all positions?") },
        text = { Text("${open.size} open ${if (open.size == 1) "position" else "positions"} will be closed at market price.") },
        confirmButton = { TextButton({ confirmExitAll = false; open.forEach(vm::closePosition) }, Modifier.testTag("confirm_exit_all")) { Text("Exit all") } },
        dismissButton = { TextButton({ confirmExitAll = false }) { Text("Cancel") } })
}

private fun signedRupee(v: BigDecimal) = (if (v.signum() > 0) "+" else "") + Fmt.rupee(v)
private val posDate = java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yy", java.util.Locale.ENGLISH)

/** "NIFTY 29-Sep-26 22450 PE", or "RELIANCE 24-Nov-26" for a future (no FUT tag), all regular weight; a stock is just its ticker. */
private fun posTitle(id: String, info: com.papertrade.app.data.market.InstrumentInfo?) = buildAnnotatedString {
    val e = info?.expiry
    if (e == null) { append(id.substringAfter(':').removeSuffix("-EQ")); return@buildAnnotatedString }
    append(info.underlying ?: id.substringAfter(':'))
    append(" ${e.format(posDate)}" + (info.optionType?.let { " ${info.strike?.stripTrailingZeros()?.toPlainString() ?: ""} ${it.name}" } ?: ""))
}

@Composable
private fun Chip(text: String, color: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color = color.copy(alpha = 0.14f)) = Box(Modifier.height(19.dp).background(bg, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp),
    contentAlignment = Alignment.Center) { Text(text, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, color = color) }

/**
 * Open:   NIFTY 29-Sep-26 22450 PE ......... +₹191.10      Closed (greyed):  NIFTY 06-Oct-26 22300 PE ... +₹572.00
 *         1 Lot • Avg. ₹8.18 ..... LTP ₹9.10 (+133.33%)                      1 Lot • CF ...... LTP ₹46.10 (231.65%)
 *         [BUY] [CF] (1 Lot = 65) ...... EXIT AT MARKET                      Buy ₹37.45 ............... Sell ₹46.25
 * [booked] is (lots traded today, average buy, average sell) for a closed position, null for an open one.
 */
@Composable
private fun PositionCard(vm: AppVM, p: Position, pnl: BigDecimal, q: com.papertrade.app.data.market.MarketQuote?, booked: Triple<Int, BigDecimal?, BigDecimal?>?,
                         onExit: () -> Unit, onClick: () -> Unit) {
    val info = vm.info(p.instrumentId); val dim = MaterialTheme.colorScheme.onSurfaceVariant; val ink = MaterialTheme.colorScheme.onSurface
    // 100 dp a card: three 20 dp lines, 4 and 8 dp apart.
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).alpha(if (p.isOpen) 1f else 0.5f).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp)
        .testTag("pos_${p.instrumentId}")) {
        // Title regular in charcoal, P&L medium in the softer card green/red, the second line smaller in grey.
        val pnlColor = when (pnl.signum()) { 1 -> CardGain; -1 -> CardLoss; else -> CardSub }
        Row(Modifier.height(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(posTitle(p.instrumentId, info), fontSize = 16.sp, lineHeight = 22.sp, color = CardInk, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(signedRupee(pnl), fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = pnlColor)
        }
        Row(Modifier.padding(top = 4.dp).height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            val n = booked?.first ?: kotlin.math.abs(p.quantity)
            Text("$n ${if (n == 1) "Lot" else "Lots"}", fontSize = 13.sp, lineHeight = 18.sp, color = CardSub)
            Text("  •  ", fontSize = 12.sp, lineHeight = 18.sp, color = CardSub)
            if (booked == null) Text("Avg. ${Fmt.rupee(p.avgPrice)}", fontSize = 13.sp, lineHeight = 18.sp, color = CardSub) else Chip("CF", CardInk, CfBg)
            Spacer(Modifier.weight(1f))
            Text("LTP ${Fmt.rupee(q?.ltp)}  ", fontSize = 13.sp, lineHeight = 18.sp, color = CardSub)
            Text("(${Fmt.pct(q?.changePct)})", fontSize = 13.sp, lineHeight = 18.sp, color = when (q?.changePct?.signum()) { 1 -> CardGain; -1 -> CardLoss; else -> CardSub }) // the day's move, not the P&L
        }
        Row(Modifier.padding(top = 8.dp).height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (booked == null) {
                if (p.quantity > 0) Chip("BUY", BuyInk, BuyBg) else Chip("SELL", Loss); Spacer(Modifier.width(6.dp)); Chip("CF", CardInk, CfBg)
                Text("  (1 Lot = ${info?.lotSize ?: "—"})", fontSize = 12.sp, lineHeight = 16.sp, color = CardInk, maxLines = 1, modifier = Modifier.weight(1f))
                // Closes the whole position at market straight away (paper money, so no confirmation step).
                Row(Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onExit).padding(horizontal = 2.dp, vertical = 2.dp).testTag("one_tap_exit_${p.instrumentId}"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("EXIT AT MARKET", fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, color = ExitBlue)
                }
            } else {
                Text("Buy ${Fmt.rupee(booked.second)}", fontSize = 14.sp, lineHeight = 18.sp, color = ink, modifier = Modifier.weight(1f))
                Text("Sell ${Fmt.rupee(booked.third)}", fontSize = 14.sp, lineHeight = 18.sp, color = ink)
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** The muted indigo of the Positions toolbar and EXIT AT MARKET. */
private val ExitBlue = androidx.compose.ui.graphics.Color(0xFF3754B3)

/** Position card text: charcoal title, grey second line, softer profit green and loss red. */
private val CardInk = androidx.compose.ui.graphics.Color(0xFF0F1519)
private val CardSub = androidx.compose.ui.graphics.Color(0xFF3E4954)
private val BuyInk = androidx.compose.ui.graphics.Color(0xFF297B67)
private val BuyBg = androidx.compose.ui.graphics.Color(0xFFDCF0E8)
private val CfBg = androidx.compose.ui.graphics.Color(0xFFF0F4F8)
private val CardGain = androidx.compose.ui.graphics.Color(0xFF138971)
private val CardLoss = androidx.compose.ui.graphics.Color(0xFFAD5C58)
private val TotalCheck = androidx.compose.ui.graphics.Color(0xFF007E62)

@Composable
fun HoldingsScreen(vm: AppVM, nav: Nav) {
    val acc by vm.account.collectAsState(); val v by vm.valuation.collectAsState(); val quotes by vm.quotes.collectAsState()
    val holdings = acc?.positions?.values?.filter { vm.info(it.instrumentId)?.segment == Segment.EQUITY || vm.info(it.instrumentId) == null && it.instrumentId.endsWith("-EQ") }?.filter { it.isOpen || it.realizedGross.signum() != 0 } ?: emptyList()
    val funds by vm.mutualFunds.collectAsState(); var fundSel by remember { mutableStateOf<com.papertrade.app.data.repo.MutualFundHolding?>(null) }
    LaunchedEffect(Unit) { vm.refreshFunds() }
    if (holdings.isEmpty() && funds.isEmpty()) { EmptyState(Icons.Outlined.Inventory2, "No holdings", "Stocks you buy for delivery show up here. Add ones you already own from Account."); return }
    fundSel?.let { f -> AlertDialog(onDismissRequest = { fundSel = null }, title = { Text(f.name, fontSize = 16.sp) }, text = { Column {
        StatRow("Units", f.units.toPlainString()); StatRow("Avg NAV", Fmt.rupee(f.avgNav, 4)); StatRow("Latest NAV", Fmt.rupee(f.latestNav, 4) + (f.navDate?.let { " (${it.format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))})" } ?: ""))
        StatRow("Invested", Fmt.rupee(f.invested)); StatRow("Current", Fmt.rupee(f.current)); StatRow("P&L", Fmt.signed(f.pnl), Fmt.color(f.pnl, MaterialTheme.colorScheme.onSurface))
    } }, confirmButton = { TextButton({ fundSel = null }) { Text("Close") } },
        dismissButton = { TextButton({ vm.removeFund(f.schemeCode); fundSel = null }, Modifier.testTag("fund_remove")) { Text("Remove", color = MaterialTheme.colorScheme.error) } }) }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
        if (funds.isNotEmpty() && holdings.isNotEmpty()) item(key = "eq_header") { Text("Stocks", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)) }
        items(holdings, key = { it.instrumentId }) { p -> val r = v?.positions?.firstOrNull { it.instrumentId == p.instrumentId }; val q = quotes[p.instrumentId]
            val pnl = r?.unrealized?.let { it + p.realizedGross } ?: p.realizedGross
            // Flat row: name / "qty shares · Avg ₹x" on the left; P&L / "LTP ₹y" on the right.
            Column(Modifier.fillMaxWidth().clickable { nav.stock(p.instrumentId) }.testTag("holding_${p.instrumentId}")) {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.instrumentId.substringAfter(':').removeSuffix("-EQ"), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Text("${p.quantity} shares · Avg ${Fmt.rupee(p.avgPrice)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text((if (pnl.signum() > 0) "+" else "") + Fmt.rupee(pnl), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Fmt.color(pnl, MaterialTheme.colorScheme.onSurface))
                        Text("LTP ${Fmt.rupee(q?.ltp)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        if (funds.isNotEmpty()) {
            item(key = "mf_header") { Text("Mutual funds", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp)) }
            items(funds, key = { "mf_" + it.schemeCode }) { f ->
                Column(Modifier.fillMaxWidth().clickable { fundSel = f }.testTag("fund_${f.schemeCode}")) {
                    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(f.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text("${f.units.toPlainString()} units · Avg ${Fmt.rupee(f.avgNav)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text((if (f.pnl.signum() > 0) "+" else "") + Fmt.rupee(f.pnl), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Fmt.color(f.pnl, MaterialTheme.colorScheme.onSurface))
                            Text("NAV ${Fmt.rupee(f.nav)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(vm: AppVM, nav: Nav) {
    val acc by vm.account.collectAsState(); val v by vm.valuation.collectAsState(); val settings by vm.settings.collectAsState(); val ctx: Context = LocalContext.current
    val auth by vm.fyersAuth.collectAsState(); val upstoxAuth by vm.upstoxAuth.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<AddKind?>(null) }; val funds by vm.mutualFunds.collectAsState()
    adding?.let { k -> AddHoldingDialog(vm, k) { adding = null } }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri?.let { vm.export { text -> ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(text.toByteArray()) } } } }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.import(ctx.contentResolver.openInputStream(it)?.bufferedReader()?.readText() ?: "") } }
    Scaffold(topBar = { TopAppBar(title = { Text("Account & settings") }, navigationIcon = { TextButton(nav.back) { Text("←") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text("Virtual wallet (simulation only)", fontWeight = FontWeight.SemiBold)
                StatRow("Starting capital", Fmt.rupee(acc?.wallet?.startingCapital)); StatRow("Available cash", Fmt.rupee(v?.availableCash)); StatRow("Reserved margin", Fmt.rupee(v?.reservedMargin))
                StatRow("Realized P&L", Fmt.signed(v?.realizedGross)); StatRow("Unrealized P&L", Fmt.signed(v?.unrealized)); StatRow("Charges", Fmt.rupee(v?.totalCharges))
                StatRow("Net P&L", Fmt.signed(v?.netPnl), Fmt.color(v?.netPnl, MaterialTheme.colorScheme.onSurface)); StatRow("Total equity", Fmt.rupee(v?.equity), Color.Unspecified)
            } }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Add to portfolio", fontWeight = FontWeight.SemiBold)
                Text("Bring in stocks or mutual funds you already hold, at your own buy price. Cash is not used.", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ adding = AddKind.STOCK }, Modifier.testTag("add_stock")) { Text("Add stock") }
                    OutlinedButton({ adding = AddKind.FUND }, Modifier.testTag("add_fund")) { Text("Add mutual fund") }
                }
                if (funds.isNotEmpty()) {
                    val inv = funds.fold(BigDecimal.ZERO) { s, f -> s + f.invested }; val cur = funds.fold(BigDecimal.ZERO) { s, f -> s + f.current }
                    StatRow("Mutual funds invested", Fmt.rupee(inv)); StatRow("Mutual funds current", Fmt.rupee(cur))
                    StatRow("Mutual funds P&L", Fmt.signed(cur - inv), Fmt.color(cur - inv, MaterialTheme.colorScheme.onSurface))
                }
            } }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Market data", fontWeight = FontWeight.SemiBold)
                // One toggle: synthetic demo prices, or real prices from FYERS or Upstox. Saved symbols work with either broker.
                val choice = if (settings.dataMode == DataMode.DEMO) null else settings.liveSource
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(choice == null, { vm.saveSettings(settings.copy(dataMode = DataMode.DEMO)) }, { Text("Demo") }, modifier = Modifier.testTag("mode_DEMO"))
                    LiveSource.values().forEach { s ->
                        FilterChip(choice == s, { vm.saveSettings(settings.copy(dataMode = DataMode.LIVE_PROVIDER, liveSource = s)) }, { Text(s.displayName) }, modifier = Modifier.testTag("mode_${s.name}"))
                    }
                }
                Text(if (choice == null) "Synthetic prices, no login needed." else "Real prices from ${choice.displayName}. Trades are still simulated.", fontSize = 12.sp)
            } }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BrokerLogin("FYERS", auth, when (auth) {
                    LoginState.READY -> "Logged in. Renews itself every day" + (vm.fyersLoginValidUntil()?.let { "; next login needed around ${Fmt.time(it)}" } ?: "") + "."
                    LoginState.NEEDS_LOGIN -> "Not logged in (or the 15-day login ran out). Tap Log in."
                    LoginState.NOT_SET_UP -> "App details not set up yet (one time, see Setup below)."
                }, "fyers", nav.fyersLogin, vm::logoutFyers,
                    "From https://myapi.fyers.in/dashboard/. Only a hash of the secret is stored. The redirect URI must exactly match the one registered for the FYERS app.",
                    "FYERS app id", vm::setupFyers)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(settings.fyersPrime, { vm.saveSettings(settings.copy(fyersPrime = it)) }, Modifier.testTag("fyers_prime"))
                    Text("  Premium (Prime) plan")
                }
                Text(if (settings.fyersPrime) "Higher FYERS limits: updates every second if the live stream drops, up to 5,000 contracts streamed."
                    else "Free Standard plan: 5,000 requests a day, so the app streams prices and only falls back to updates every 3 seconds. Turn on only if the account has Prime.",
                    fontSize = 12.sp)
            } }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BrokerLogin("Upstox", upstoxAuth, when (upstoxAuth) {
                    LoginState.READY -> "Logged in until ${vm.upstoxLoginValidUntil()?.let { Fmt.time(it) } ?: "3:30 AM"}. Upstox needs a fresh login every day."
                    LoginState.NEEDS_LOGIN -> "Not logged in. Upstox logins end at 3:30 AM every day. Tap Log in."
                    LoginState.NOT_SET_UP -> "App details not set up yet (one time, see Setup below)."
                }, "upstox", nav.upstoxLogin, vm::logoutUpstox,
                    "From https://account.upstox.com/developer/apps. The API secret is stored encrypted on this phone. The redirect URI must exactly match the one registered for the Upstox app.",
                    "Upstox API key", vm::setupUpstox)
            } }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Simulation rules", fontWeight = FontWeight.SemiBold)
                IntField("Max quote age (seconds)", settings.maxQuoteAgeSec) { vm.saveSettings(settings.copy(maxQuoteAgeSec = it.coerceIn(1, 3600))) }
                IntField("LTP-only slippage (bps)", settings.slippageBps) { vm.saveSettings(settings.copy(slippageBps = it.coerceIn(0, 1000))) }
                IntField("Partial-fill cap per tick (0 = off)", settings.partialFillCap) { vm.saveSettings(settings.copy(partialFillCap = it.coerceAtLeast(0))) }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Switch(settings.chargesEnabled, { vm.saveSettings(settings.copy(chargesEnabled = it)) }); Text("  Apply illustrative charges") }
                Text("Charge rates and margin percentages are illustrative assumptions, not official broker/exchange figures.", fontSize = 11.sp)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(settings.testClosedMarket, { vm.saveSettings(settings.copy(testClosedMarket = it)) }, Modifier.testTag("test_closed_market")); Text("  Test trading when the market is closed")
                }
                Text(if (settings.testClosedMarket) "On: orders fill at the last available price, even after hours. Turn off for normal trading."
                    else "Off: market orders need a live price, as in the real market.", fontSize = 11.sp)
            } }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Backup", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ exportLauncher.launch("papertrade-export.json") }) { Text("Export") }; OutlinedButton({ importLauncher.launch(arrayOf("application/json", "text/plain")) }) { Text("Import") } }
                Button({ confirmReset = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Reset simulation to ₹50,00,000") }
            } }
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text("Reset simulation?") }, text = { Text("All orders, trades, positions, holdings and added mutual funds will be deleted. Export first if you want a backup.") },
        confirmButton = { TextButton({ vm.reset(); confirmReset = false }) { Text("Reset") } }, dismissButton = { TextButton({ confirmReset = false }) { Text("Cancel") } })
}

enum class AddKind { STOCK, FUND }

/**
 * Search a stock (from the market data provider) or a mutual fund (AMFI), then enter the buy price and units. Stock units are
 * whole shares; fund units may have decimals.
 */
@Composable
private fun AddHoldingDialog(vm: AppVM, kind: AddKind, onDismiss: () -> Unit) {
    val fund = kind == AddKind.FUND
    var query by remember { mutableStateOf("") }; var results by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var stocks by remember { mutableStateOf<Map<String, com.papertrade.app.data.market.InstrumentInfo>>(emptyMap()) }
    var picked by remember { mutableStateOf<Pair<String, String>?>(null) }; var error by remember { mutableStateOf<String?>(null) }; var searching by remember { mutableStateOf(false) }
    var price by remember { mutableStateOf("") }; var units by remember { mutableStateOf("") }
    LaunchedEffect(query, picked) {
        if (picked != null || query.trim().length < 2) { results = emptyList(); error = null; return@LaunchedEffect }
        kotlinx.coroutines.delay(350); searching = true
        if (fund) vm.searchFunds(query).onSuccess { l -> results = l.take(8).map { it.code to it.name }; error = if (l.isEmpty()) "No fund found" else null }.onFailure { error = it.message }
        else vm.search(query).onSuccess { l -> val eq = l.filter { it.segment == Segment.EQUITY }.take(8); stocks = eq.associateBy { it.symbol }
            results = eq.map { it.symbol to it.name }; error = if (eq.isEmpty()) "No stock found" else null }.onFailure { error = it.message }
        searching = false
    }
    val p = price.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    val u = if (fund) units.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } else units.toIntOrNull()?.takeIf { it > 0 }?.let(::BigDecimal)
    val total = if (p != null && u != null) Dec.money(p * u) else null
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (fund) "Add mutual fund" else "Add stock") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val sel = picked
            if (sel == null) {
                OutlinedTextField(query, { query = it }, label = { Text(if (fund) "Fund name" else "Stock name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("add_query"))
                if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }
                Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                    results.forEach { r ->
                        Column(Modifier.fillMaxWidth().clickable { picked = r }.padding(vertical = 8.dp).testTag("add_result_${r.first}")) {
                            Text(if (fund) r.second else r.first.substringAfter(':').removeSuffix("-EQ"), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            if (!fund) Text(r.second, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (fund) sel.second else sel.first.substringAfter(':').removeSuffix("-EQ"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    TextButton({ picked = null }) { Text("Change") }
                }
                OutlinedTextField(price, { s -> price = s.filter { it.isDigit() || it == '.' } }, label = { Text(if (fund) "Buy NAV (₹)" else "Buy price (₹)") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag("add_price"))
                OutlinedTextField(units, { s -> units = s.filter { it.isDigit() || fund && it == '.' } }, label = { Text(if (fund) "Units" else "Shares") }, singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (fund) androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Number), modifier = Modifier.fillMaxWidth().testTag("add_units"))
                Text("Invested ${Fmt.rupee(total)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }, confirmButton = {
        TextButton({
            val s = picked ?: return@TextButton; if (p == null || u == null) return@TextButton
            if (fund) vm.addFund(s.first, s.second, u, p, onDismiss) else stocks[s.first]?.let { vm.addHolding(it, u.toInt(), p, onDismiss) }
        }, enabled = picked != null && p != null && u != null, modifier = Modifier.testTag("add_confirm")) { Text("Add") }
    }, dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

/** Login status, Log in / Log out, and the one-time app setup (key, secret, redirect URI) for one broker. */
@Composable
private fun BrokerLogin(broker: String, auth: LoginState, statusText: String, tag: String, onLogin: () -> Unit, onLogout: () -> Unit,
                        setupHelp: String, keyLabel: String, onSetup: (String, String, String) -> Unit) {
    var showSetup by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }; var secret by remember { mutableStateOf("") }; var redirect by remember { mutableStateOf("") }
    Text(broker, fontWeight = FontWeight.SemiBold)
    Text(statusText, fontSize = 12.sp, modifier = Modifier.testTag("${tag}_status"))
    Row { Button(onLogin, enabled = auth != LoginState.NOT_SET_UP, modifier = Modifier.testTag("${tag}_login")) { Text("Log in to $broker") }; Spacer(Modifier.width(8.dp)); OutlinedButton(onLogout, enabled = auth == LoginState.READY) { Text("Log out") } }
    TextButton({ showSetup = !showSetup }) { Text(if (showSetup) "Hide setup" else "Setup (app owner, one time)") }
    if (showSetup) {
        Text(setupHelp, fontSize = 12.sp)
        OutlinedTextField(key, { key = it }, label = { Text(keyLabel) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(secret, { secret = it }, label = { Text("App secret") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(redirect, { redirect = it }, label = { Text("Redirect URI") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button({ onSetup(key, secret, redirect); secret = ""; showSetup = false }, enabled = key.isNotBlank() && secret.isNotBlank() && redirect.isNotBlank()) { Text("Save setup") }
    }
}

@Composable
private fun IntField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(text, { text = it.filter(Char::isDigit); text.toIntOrNull()?.let(onChange) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}
private typealias Color = androidx.compose.ui.graphics.Color
