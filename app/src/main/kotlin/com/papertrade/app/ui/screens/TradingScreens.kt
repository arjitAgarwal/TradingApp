package com.papertrade.app.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.shadow
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
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.foundation.border
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

private enum class HoldTab(val label: String) { OVERVIEW("Overview"), EQUITY("Equity"), FUNDS("Mutual Funds"), PICKS("Investment Picks") }
private val WealthTop = androidx.compose.ui.graphics.Color(0xFF2F3E52)
private val WealthBottom = androidx.compose.ui.graphics.Color(0xFF1E2A39)
private val WealthBody = androidx.compose.ui.graphics.Color(0xFF2A394E)
private val WealthStrip = androidx.compose.ui.graphics.Color(0xFF202B3F)
private val WealthGain = androidx.compose.ui.graphics.Color(0xFF45B5A0)
private val WealthLoss = androidx.compose.ui.graphics.Color(0xFFF16564)
/** The Portfolio's own loss red and gain green on white (Overview's Assets rows, Equity cards), as measured from the reference. */
private val AssetLoss = androidx.compose.ui.graphics.Color(0xFFD24B4A)
private val AssetGain = androidx.compose.ui.graphics.Color(0xFF009175)
/** [AssetGain], [AssetLoss], or [neutral] for zero and unknown. */
private fun pnlInk(v: BigDecimal?, neutral: androidx.compose.ui.graphics.Color) = when { v == null || v.signum() == 0 -> neutral; v.signum() > 0 -> AssetGain; else -> AssetLoss }

/** The warm, faint tint of the summary card's diagonal band. */
private val BandTint = androidx.compose.ui.graphics.Color(0xFFF8DEAE)

/** Portfolio text inks in the light theme: near-black titles, blue-slate second lines and tab labels. Dark theme falls back to the scheme. */
@Composable private fun titleInk() = if (androidx.compose.foundation.isSystemInDarkTheme()) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color(0xFF182028)
@Composable private fun subInk() = if (androidx.compose.foundation.isSystemInDarkTheme()) MaterialTheme.colorScheme.onSurfaceVariant else androidx.compose.ui.graphics.Color(0xFF46535D)
@Composable private fun tabInk() = if (androidx.compose.foundation.isSystemInDarkTheme()) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color(0xFF414E5F)
/** Equity card inks in the light theme: dark slate values and grey ATP / LTP labels. */
@Composable private fun cardInk() = if (androidx.compose.foundation.isSystemInDarkTheme()) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color(0xFF2E3940)
@Composable private fun labelInk() = if (androidx.compose.foundation.isSystemInDarkTheme()) MaterialTheme.colorScheme.onSurfaceVariant else androidx.compose.ui.graphics.Color(0xFF69737A)
/** The pale page colour behind the Equity list and between the Overview's white blocks. */
@Composable private fun paleBg() = if (androidx.compose.foundation.isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color(0xFFF5F6FB)
/** The thin pale strip between the white blocks of the Overview page. */
@Composable private fun BlockGap() = Spacer(Modifier.fillMaxWidth().height(6.dp).background(paleBg()))
private val PageGrey = androidx.compose.ui.graphics.Color(0xFFF2F4F8)

/** Totals for one asset class: what was paid, what it is worth now, and today's move (null when no previous close is known). */
private data class AssetSum(val invested: BigDecimal, val current: BigDecimal, val today: BigDecimal?) {
    val pnl get() = current - invested
    fun pct(v: BigDecimal) = if (invested.signum() == 0) BigDecimal.ZERO else Dec.div(v * BigDecimal(100), invested)
}

/**
 * Portfolio, Holdings page: Overview / Equity / Mutual Funds tabs. Overview is a dark summary card (current value, overall and
 * today's gain, invested value; the eye hides amounts) over an Assets list. Equity and Mutual Funds list the rows.
 */
@Composable
fun HoldingsScreen(vm: AppVM, nav: Nav) {
    val acc by vm.account.collectAsState(); val v by vm.valuation.collectAsState(); val quotes by vm.quotes.collectAsState()
    val holdings = acc?.positions?.values?.filter { vm.info(it.instrumentId)?.segment == Segment.EQUITY || vm.info(it.instrumentId) == null && it.instrumentId.endsWith("-EQ") }?.filter { it.isOpen || it.realizedGross.signum() != 0 } ?: emptyList()
    val funds by vm.mutualFunds.collectAsState(); var fundSel by remember { mutableStateOf<com.papertrade.app.data.repo.MutualFundHolding?>(null) }
    var tab by rememberSaveable { mutableStateOf(HoldTab.OVERVIEW) }; var adding by remember { mutableStateOf<AddKind?>(null) }
    var hidden by rememberSaveable { mutableStateOf(false) }; val soon = { vm.messages.tryEmit("Coming soon"); Unit }
    adding?.let { k -> AddHoldingDialog(vm, k) { adding = null } }
    LaunchedEffect(Unit) { vm.refreshFunds() }
    // Open stocks at the live price (buy price until one arrives); today's move needs the previous close. Funds have one NAV a day, no day change.
    val held = holdings.filter { it.isOpen }
    val eq = AssetSum(held.fold(BigDecimal.ZERO) { s, p -> s + Dec.money(p.avgPrice * BigDecimal(p.quantity)) },
        held.fold(BigDecimal.ZERO) { s, p -> s + Dec.money((quotes[p.instrumentId]?.ltp ?: p.avgPrice) * BigDecimal(p.quantity)) },
        held.mapNotNull { p -> quotes[p.instrumentId]?.let { q -> q.prevClose?.let { pc -> Dec.money((q.ltp - pc) * BigDecimal(p.quantity)) } } }.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO, BigDecimal::add))
    val mf = AssetSum(funds.fold(BigDecimal.ZERO) { s, f -> s + f.invested }, funds.fold(BigDecimal.ZERO) { s, f -> s + f.current }, null)
    Column(Modifier.fillMaxSize()) {
        // Scrolls sideways: each tab is at least 72 dp wide with 12 dp side padding, and its underline is as wide as its label.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            HoldTab.values().forEach { t -> val on = t == tab
                Box(Modifier.clickable { tab = t }.widthIn(min = 72.dp).height(48.dp).padding(horizontal = 12.dp).testTag("hold_tab_${t.name}"), contentAlignment = Alignment.Center) {
                    Column(Modifier.width(IntrinsicSize.Max).fillMaxHeight()) {
                        // The label sits a little below the middle of the row, as in the reference.
                        Box(Modifier.weight(1f).fillMaxWidth().padding(top = 4.5.dp), contentAlignment = Alignment.Center) { Text(t.label, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false, color = if (on) Accent else tabInk()) }
                        Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) Accent else Color.Transparent))
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        when (tab) {
            HoldTab.OVERVIEW -> HoldingsOverview(eq, mf, held.isNotEmpty(), funds.isNotEmpty(), held.map { p -> p.instrumentId.substringAfter(":").removeSuffix("-EQ") to quotes[p.instrumentId]?.changePct },
                onEquity = { tab = HoldTab.EQUITY }, onFunds = { tab = HoldTab.FUNDS }, onAdd = { adding = it }, onSoon = soon, hidden = hidden, onEye = { hidden = !hidden })
            HoldTab.EQUITY -> if (holdings.isEmpty()) EmptyState(Icons.Outlined.Inventory2, "No stocks", "Stocks you buy for delivery show up here.") { TextButton({ adding = AddKind.STOCK }) { Text("+ Add a stock you already own") } }
                else EquityTab(holdings, eq, quotes, v, hidden, { hidden = !hidden }, nav, { adding = AddKind.STOCK }, soon)
            HoldTab.FUNDS -> if (funds.isEmpty()) EmptyState(Icons.Outlined.Savings, "No mutual funds", "Add funds you already hold to track them at the latest NAV.") { TextButton({ adding = AddKind.FUND }) { Text("+ Add a mutual fund") } }
                else FundRows(funds, { fundSel = it }) { adding = AddKind.FUND }
            HoldTab.PICKS -> EmptyState(Icons.Outlined.Insights, "Investment Picks", "Coming soon")
        }
    }
    fundSel?.let { f -> AlertDialog(onDismissRequest = { fundSel = null }, title = { Text(f.name, fontSize = 16.sp) }, text = { Column {
        StatRow("Units", f.units.toPlainString()); StatRow("Avg NAV", Fmt.rupee(f.avgNav, 4)); StatRow("Latest NAV", Fmt.rupee(f.latestNav, 4) + (f.navDate?.let { " (${it.format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))})" } ?: ""))
        StatRow("Invested", Fmt.rupee(f.invested)); StatRow("Current", Fmt.rupee(f.current)); StatRow("P&L", Fmt.signed(f.pnl), Fmt.color(f.pnl, MaterialTheme.colorScheme.onSurface))
    } }, confirmButton = { TextButton({ fundSel = null }) { Text("Close") } },
        dismissButton = { TextButton({ vm.removeFund(f.schemeCode); fundSel = null }, Modifier.testTag("fund_remove")) { Text("Remove", color = MaterialTheme.colorScheme.error) } }) }
}

/**
 * Overview tab: the dark summary card (with the look-only VERIFY TO SELL strip), the Assets list (Equity, Mutual Funds; ◀ ▶ flips the rows
 * between overall and today's gain), then the look-only MTF Borrowing and Start SIP rows, Tools grid and News strip.
 */
@Composable
private fun HoldingsOverview(eq: AssetSum, mf: AssetSum, hasEq: Boolean, hasMf: Boolean, movers: List<Pair<String, BigDecimal?>>, onEquity: () -> Unit, onFunds: () -> Unit, onAdd: (AddKind) -> Unit, onSoon: () -> Unit, hidden: Boolean, onEye: () -> Unit) {
    var todayView by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).verticalScroll(rememberScrollState())) {
        WealthCard(eq.current + mf.current, eq.invested + mf.invested, eq.today, hidden, onEye, onAnalyze = null, onVerify = onSoon, todayOn = eq.current)
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 11.5.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Assets", fontSize = 12.sp, lineHeight = 16.sp, color = subInk(), modifier = Modifier.weight(1f))
            // The switch rides 2 dp above the "Assets" line.
            Row(Modifier.offset(y = (-2).dp).clip(RoundedCornerShape(4.dp)).clickable { todayView = !todayView }.padding(vertical = 1.dp).testTag("assets_switch"), verticalAlignment = Alignment.CenterVertically) {
                SwitchArrows(); Spacer(Modifier.width(7.dp))
                Text(if (todayView) "Today's Gain/Loss" else "Overall Gain/Loss", fontSize = 10.sp, lineHeight = 14.sp, color = subInk())
            }
        }
        AssetRow({ EquityIcon() }, "EQUITY", eq, hasEq, todayView, hidden, "Buy stocks for delivery, or add ones you own", "ADD STOCK", { onAdd(AddKind.STOCK) }, onEquity, "asset_equity")
        HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        AssetRow({ FundIcon() }, "MUTUAL FUNDS", mf, hasMf, todayView, hidden, "Start SIPs with just ", "ADD FUND", { onAdd(AddKind.FUND) }, onFunds, "asset_funds", emptyStrong = "₹500")
        BlockGap()
        PromoRow({ MtfIcon() }, "MTF Borrowing", "Maximize your gains by 4x", "GET STARTED", onSoon, "promo_mtf")
        BlockGap()
        PromoRow({ SipIcon() }, "Start SIP", "Invest regularly, grow steadily", "START A SIP", onSoon, "promo_sip")
        BlockGap()
        ToolsGrid(onSoon)
        BlockGap()
        NewsStrip(movers, onSoon)
        Spacer(Modifier.height(16.dp))
    }
}

/** The ◀ ▶ pair beside "Overall Gain/Loss": two solid triangles. */
@Composable
private fun SwitchArrows() { val c = subInk()
    androidx.compose.foundation.Canvas(Modifier.offset(y = (-0.5).dp).size(width = 18.7.dp, height = 10.5.dp)) {
        val w = size.width; val h = size.height; val t = 6.2.dp.toPx()
        drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(t, 0f); lineTo(t, h); lineTo(0f, h / 2); close() }, c)
        drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(w - t, 0f); lineTo(w - t, h); lineTo(w, h / 2); close() }, c)
    }
}

/** The small outlined button of the Overview rows: 93 x 24 dp, blue outline and label, a faint shadow under it. */
@Composable
private fun SmallOutlined(label: String, onClick: () -> Unit) { val shape = RoundedCornerShape(4.dp)
    Box(Modifier.shadow(1.5.dp, shape).size(width = 93.dp, height = 24.dp).clip(shape).background(MaterialTheme.colorScheme.surface).border(1.dp, Accent, shape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, color = Accent, maxLines = 1, softWrap = false)
    }
}

/**
 * The dark summary card shared by Overview and Equity: current value with the eye (hides amounts), overall gain or loss, invested value
 * and today's gain or loss, arrows in green or red. A darker strip at the bottom holds ANALYZE (Equity only) and VERIFY TO SELL, both look only.
 * On the Equity card a gain today is written "+ ₹…", as in the reference.
 */
@Composable
private fun WealthCard(current: BigDecimal, invested: BigDecimal, today: BigDecimal?, hidden: Boolean, onEye: () -> Unit, onAnalyze: (() -> Unit)?, onVerify: () -> Unit, todayOn: BigDecimal = current) {
    val overall = current - invested
    val overallPct = if (invested.signum() == 0) BigDecimal.ZERO else Dec.div(overall * BigDecimal(100), invested)
    val todayBase = todayOn - (today ?: BigDecimal.ZERO) // today's % is on what moved today (stocks), not on funds
    val todayPct = if (today == null || todayBase.signum() == 0) null else Dec.div(today * BigDecimal(100), todayBase)
    fun amt(v: BigDecimal, d: Int = 2) = if (hidden) "₹ ••••" else Fmt.rupee(v, d)
    fun pctText(v: BigDecimal) = "(" + Fmt.pct(v) + ")"
    val white = Color.White; val loss = overall.signum() < 0; val down = (today ?: BigDecimal.ZERO).signum() < 0
    val oc = if (loss) WealthLoss else WealthGain; val tc = if (down) WealthLoss else WealthGain
    // An amount in its colour with its white percentage after it, as one line so the two share a baseline.
    fun withPct(amount: String, ink: Color, pct: BigDecimal?) = buildAnnotatedString {
        withStyle(SpanStyle(color = ink)) { append(amount) }
        pct?.let { withStyle(SpanStyle(letterSpacing = 1.25.sp)) { append(" ") }; withStyle(SpanStyle(fontSize = 12.sp)) { append(pctText(it)) } }
    }
    Column(Modifier.padding(8.dp).fillMaxWidth().clip(RoundedCornerShape(7.dp)).background(WealthBody).testTag("wealth_card")) {
        Column(Modifier.fillMaxWidth()
            .drawBehind { // the faint warm diagonal band: strongest at the top, almost gone where the strip starts
                val w = size.width; val h = size.height
                drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(0.36f * w, 0f); lineTo(0.62f * w, 0f); lineTo(0.835f * w, h); lineTo(0.575f * w, h); close() },
                    androidx.compose.ui.graphics.Brush.verticalGradient(listOf(BandTint.copy(alpha = 0.078f), Color.Transparent), 0f, 1.19f * h))
            }.padding(start = 14.dp, end = 12.dp, top = 15.dp, bottom = 15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(amt(current, 0), fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, color = white, modifier = Modifier.testTag("wealth_current"))
                Spacer(Modifier.width(4.dp))
                Icon(if (hidden) NavIcons.eyeOff else NavIcons.eye, if (hidden) "Show amounts" else "Hide amounts", Modifier.size(18.dp).clip(RoundedCornerShape(50)).clickable(onClick = onEye).testTag("wealth_eye"), tint = white)
            }
            // The arrow's ink starts where the text's ink does (1 dp in); amount and percent sit 1 dp below the label's line.
            Row(Modifier.height(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(1.dp)); Icon(if (loss) NavIcons.arrowDown else NavIcons.arrowUp, null, Modifier.offset(y = 0.5.dp).size(width = 7.5.dp, height = 8.75.dp), tint = oc); Spacer(Modifier.width(3.5.dp))
                Text("Overall " + (if (loss) "Loss" else "Gain"), fontSize = 12.sp, lineHeight = 16.sp, color = white); Spacer(Modifier.width(3.5.dp))
                Text(withPct(amt(overall), oc, overallPct), fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = white, maxLines = 1, softWrap = false, modifier = Modifier.offset(y = 1.dp))
            }
            Row(Modifier.padding(top = 10.dp)) {
                Column(Modifier.weight(1f)) { Text("Invested Value", fontSize = 12.sp, lineHeight = 16.sp, color = white); Text(amt(invested, 0), fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = white, modifier = Modifier.padding(top = 1.dp)) }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(if (down) NavIcons.arrowDown else NavIcons.arrowUp, null, Modifier.size(width = 8.1.dp, height = 9.4.dp), tint = tc); Spacer(Modifier.width(3.dp)); Text("Today's " + (if (down) "Loss" else "Gain"), fontSize = 12.sp, lineHeight = 16.sp, color = white) }
                    Text(withPct(today?.let { (if (onAnalyze != null && !hidden && it.signum() > 0) "+ " else "") + amt(it) } ?: "—", tc, todayPct), fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = white, maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 1.dp).testTag("wealth_today"))
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(WealthStrip).padding(start = 14.dp, end = 20.dp, top = 8.dp, bottom = 11.5.dp), verticalAlignment = Alignment.CenterVertically) {
            onAnalyze?.let { a -> Row(Modifier.clickable(onClick = a).testTag("analyze"), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(1.dp)); PulseIcon(); Spacer(Modifier.width(4.5.dp))
                Text("ANALYZE", fontSize = 12.sp, lineHeight = 16.sp, color = VerifyCyan)
            } }
            Spacer(Modifier.weight(1f))
            Row(Modifier.clickable(onClick = onVerify).testTag("verify_to_sell"), verticalAlignment = Alignment.CenterVertically) {
                Text("VERIFY TO SELL", fontSize = 12.sp, lineHeight = 16.sp, color = VerifyCyan)
                Spacer(Modifier.width(2.dp)); Icon(NavIcons.lock, null, Modifier.size(12.dp), tint = VerifyCyan)
            }
        }
    }
}

/**
 * Equity tab: the summary card for stocks alone (with ANALYZE) on white, then on a pale page a search box with a filter button, one
 * raised card per stock held ("qty X ATP avg", P&L with %, LTP with the day's %), closed stocks faded under INACTIVE STOCKS, and the
 * look-only TPIN strip pinned at the bottom.
 */
@Composable
private fun EquityTab(holdings: List<Position>, eq: AssetSum, quotes: Map<String, com.papertrade.app.data.market.MarketQuote>, v: PortfolioValuation?, hidden: Boolean, onEye: () -> Unit,
                      nav: Nav, onAdd: () -> Unit, onSoon: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    fun name(p: Position) = p.instrumentId.substringAfter(':').removeSuffix("-EQ")
    val shown = holdings.filter { query.isBlank() || name(it).contains(query.trim(), ignoreCase = true) }
    val active = shown.filter { it.isOpen }; val inactive = shown.filter { !it.isOpen }
    val ink = cardInk(); val label = labelInk()
    // One stock: 72.5 dp tall, the two lines sitting below the middle as in the reference.
    val stockCard: @Composable (Position) -> Unit = { p ->
        val q = quotes[p.instrumentId]
        val pnl = if (p.isOpen) Dec.money(((q?.ltp ?: p.avgPrice) - p.avgPrice) * BigDecimal(p.quantity)) else p.realizedGross
        val cost = Dec.money(p.avgPrice * BigDecimal(p.quantity)); val pnlPct = if (p.isOpen && cost.signum() != 0) Dec.div(pnl * BigDecimal(100), cost) else null
        val day = q?.changePct ?: q?.prevClose?.takeIf { it.signum() != 0 }?.let { pc -> Dec.div((q.ltp - pc) * BigDecimal(100), pc) }
        Card(Modifier.padding(horizontal = 8.dp, vertical = 4.5.dp).fillMaxWidth().alpha(if (p.isOpen) 1f else 0.5f).clickable { nav.stock(p.instrumentId) }.testTag("holding_${p.instrumentId}"),
            shape = RoundedCornerShape(6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 19.dp, bottom = 10.5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name(p), fontSize = 16.sp, lineHeight = 20.sp, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(if (hidden) "₹ ••••" else Fmt.rupee(pnl), fontSize = 16.sp, lineHeight = 20.sp, color = pnlInk(pnl, ink), maxLines = 1)
                    pnlPct?.let { Text(" (" + Fmt.pct(it) + ")", fontSize = 12.sp, lineHeight = 16.sp, color = ink, maxLines = 1, modifier = Modifier.padding(start = 1.dp)) }
                }
                Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${p.quantity} X ", fontSize = 12.sp, lineHeight = 16.sp, color = ink); Text("ATP ", fontSize = 12.sp, lineHeight = 16.sp, color = label)
                    Text(Fmt.rupee(p.avgPrice), fontSize = 12.sp, lineHeight = 16.sp, color = ink, maxLines = 1, modifier = Modifier.weight(1f))
                    Text("LTP ", fontSize = 12.sp, lineHeight = 16.sp, color = label)
                    Text(Fmt.rupee(q?.ltp), fontSize = 12.sp, lineHeight = 16.sp, color = pnlInk(day, ink))
                    day?.let { Text(" (" + Fmt.pct(it) + ")", fontSize = 12.sp, lineHeight = 16.sp, color = ink) }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(paleBg())) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
            item(key = "card") { Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) { WealthCard(eq.current, eq.invested, eq.today, hidden, onEye, onAnalyze = onSoon, onVerify = onSoon) } }
            item(key = "search") {
                // 48 dp: thin ring icon, 14 sp text, a square filter cell.
                Row(Modifier.padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 7.5.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(13.dp)); SearchRing(); Spacer(Modifier.width(6.5.dp))
                    Box(Modifier.weight(1f).padding(end = 10.dp), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text("Search stocks by company", fontSize = 14.sp, lineHeight = 18.sp, color = subInk(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        androidx.compose.foundation.text.BasicTextField(query, { query = it }, singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Accent), modifier = Modifier.fillMaxWidth().testTag("equity_search"))
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Box(Modifier.size(48.dp).clickable(onClick = onSoon).testTag("equity_filter"), contentAlignment = Alignment.Center) { FilterIcon() }
                }
            }
            items(active, key = { it.instrumentId }) { p -> stockCard(p) }
            if (inactive.isNotEmpty()) {
                item(key = "inactive_header") { Text("INACTIVE STOCKS", fontSize = 14.sp, lineHeight = 18.sp, color = label, modifier = Modifier.padding(start = 14.dp, top = 15.dp, bottom = 7.5.dp).testTag("inactive_header")) }
                items(inactive, key = { it.instrumentId }) { p -> stockCard(p) }
            }
            if (shown.isEmpty()) item(key = "none") { Text("No stocks match", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            item(key = "add_stock") { TextButton(onAdd, Modifier.padding(horizontal = 8.dp).testTag("holdings_add_stock")) { Text("+ Add a stock you already own") } }
        }
        // Pinned reminder strip, as in the reference. Look only for now.
        Row(Modifier.fillMaxWidth().height(39.5.dp).background(TpinBg).clickable(onClick = onSoon).padding(start = 17.dp, end = 21.5.dp, top = 2.dp).testTag("tpin_strip"), verticalAlignment = Alignment.CenterVertically) {
            TpinMark(); Spacer(Modifier.width(8.5.dp))
            Text("Sell stocks without entering TPIN every time", fontSize = 12.sp, lineHeight = 16.sp, color = TpinInk, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Chevron()
        }
    }
}

private val TpinBg = androidx.compose.ui.graphics.Color(0xFFEAE4F7)
private val TpinPurple = androidx.compose.ui.graphics.Color(0xFF581EBC)
private val TpinInk = androidx.compose.ui.graphics.Color(0xFF2A2E3C)
private val VerifyCyan = androidx.compose.ui.graphics.Color(0xFF2FC3D6)
private val IconInk = androidx.compose.ui.graphics.Color(0xFF2F3A52)
private val CandleRed = androidx.compose.ui.graphics.Color(0xFFD0504F)
private val CandleGreen = androidx.compose.ui.graphics.Color(0xFF2EB67D)
private val CandleTeal = androidx.compose.ui.graphics.Color(0xFF3FA896)

/** Equity asset icon: four plain rounded bars, red and teal, under a thin rising arrow. */
@Composable
private fun EquityIcon() = androidx.compose.foundation.Canvas(Modifier.size(width = 26.dp, height = 28.dp)) {
    val w = size.width; val h = size.height; val sw = 1.25.dp.toPx(); val round = androidx.compose.ui.graphics.StrokeCap.Round
    listOf(Triple(0.14f, 0.60f, CandleRed), Triple(0.38f, 0.52f, CandleTeal), Triple(0.62f, 0.60f, CandleRed), Triple(0.86f, 0.42f, CandleTeal)).forEach { (x, top, c) ->
        drawLine(c, androidx.compose.ui.geometry.Offset(x * w, top * h), androidx.compose.ui.geometry.Offset(x * w, (top + 0.34f) * h), 0.12f * w, cap = round)
    }
    val pts = listOf(0.02f to 0.44f, 0.32f to 0.20f, 0.54f to 0.34f, 0.96f to 0.04f).map { (x, y) -> androidx.compose.ui.geometry.Offset(x * w, y * h) }
    pts.zipWithNext().forEach { (a, b) -> drawLine(IconInk, a, b, sw, cap = round) }
    val tip = pts.last()
    drawLine(IconInk, tip, androidx.compose.ui.geometry.Offset(tip.x - 0.22f * w, tip.y), sw, cap = round)
    drawLine(IconInk, tip, androidx.compose.ui.geometry.Offset(tip.x, tip.y + 0.20f * h), sw, cap = round)
}

/** Mutual funds asset icon: a thin outlined money bag with a rounded loop on top and a gold rupee coin. */
@Composable
private fun FundIcon() = Box(Modifier.size(width = 27.dp, height = 28.dp), contentAlignment = Alignment.Center) {
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height; val st = androidx.compose.ui.graphics.drawscope.Stroke(1.25.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round)
        val bag = androidx.compose.ui.graphics.Path().apply {
            moveTo(0.36f * w, 0.30f * h); cubicTo(0.10f * w, 0.42f * h, -0.02f * w, 0.70f * h, 0.06f * w, 0.86f * h); cubicTo(0.10f * w, 0.96f * h, 0.20f * w, 0.98f * h, 0.30f * w, 0.98f * h)
            lineTo(0.70f * w, 0.98f * h); cubicTo(0.80f * w, 0.98f * h, 0.90f * w, 0.96f * h, 0.94f * w, 0.86f * h); cubicTo(1.02f * w, 0.70f * h, 0.90f * w, 0.42f * h, 0.64f * w, 0.30f * h)
        }
        drawPath(bag, IconInk, style = st)
        drawRoundRect(IconInk, androidx.compose.ui.geometry.Offset(0.30f * w, 0.03f * h), androidx.compose.ui.geometry.Size(0.40f * w, 0.27f * h), androidx.compose.ui.geometry.CornerRadius(0.12f * w), style = st)
        drawCircle(androidx.compose.ui.graphics.Color(0xFFF2A93B), 0.21f * w, androidx.compose.ui.geometry.Offset(0.5f * w, 0.66f * h))
    }
    Text("₹", fontSize = 8.sp, lineHeight = 8.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.offset(y = 4.5.dp))
}

/** MTF icon: two thin rings with a purple segment between them at the top right, and a percent sign. */
@Composable
private fun MtfIcon() = Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val r = size.minDimension / 2; val sw = 1.25.dp.toPx()
        drawCircle(IconInk, r - sw / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
        drawCircle(IconInk, r * 0.60f, style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
        val mid = r * 0.79f
        drawArc(androidx.compose.ui.graphics.Color(0xFF6A3FE0), -72f, 68f, false, androidx.compose.ui.geometry.Offset(r - mid, r - mid), androidx.compose.ui.geometry.Size(mid * 2, mid * 2), style = androidx.compose.ui.graphics.drawscope.Stroke(r * 0.36f))
    }
    Text("%", fontSize = 8.sp, lineHeight = 8.sp, fontWeight = FontWeight.Bold, color = IconInk)
}

/** Start SIP icon: an outlined calendar with a solid blue top bar, two rings and six dots. */
@Composable
private fun SipIcon() = androidx.compose.foundation.Canvas(Modifier.size(width = 20.dp, height = 23.dp)) {
    val w = size.width; val h = size.height; val sw = 1.25.dp.toPx(); val rad = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
    val top = 0.13f * h; val bar = 0.30f * h
    drawRoundRect(Accent, androidx.compose.ui.geometry.Offset(sw / 2, top), androidx.compose.ui.geometry.Size(w - sw, bar), rad)
    drawRect(Accent, androidx.compose.ui.geometry.Offset(sw / 2, top + bar / 2), androidx.compose.ui.geometry.Size(w - sw, bar / 2))
    drawRoundRect(IconInk, androidx.compose.ui.geometry.Offset(sw / 2, top), androidx.compose.ui.geometry.Size(w - sw, h - top - sw / 2), rad, style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
    listOf(0.30f, 0.70f).forEach { x -> drawLine(IconInk, androidx.compose.ui.geometry.Offset(x * w, 0.02f * h), androidx.compose.ui.geometry.Offset(x * w, 0.20f * h), sw, cap = androidx.compose.ui.graphics.StrokeCap.Round) }
    listOf(0.62f, 0.80f).forEach { y -> listOf(0.27f, 0.50f, 0.73f).forEach { x -> drawCircle(IconInk, 0.05f * w, androidx.compose.ui.geometry.Offset(x * w, y * h)) } }
}

/** A white block with an icon, title and line on the left and an outlined button on the right (MTF Borrowing, Start SIP). Look only for now. */
@Composable
private fun PromoRow(icon: @Composable () -> Unit, title: String, line: String, button: String, onClick: () -> Unit, tag: String) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).height(64.dp).padding(horizontal = 12.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) { icon() }; Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(bottom = 3.5.dp)) {
            Text(title, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = titleInk(), maxLines = 1)
            Text(line, fontSize = 12.sp, lineHeight = 16.sp, color = subInk(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 0.5.dp))
        }
        Box(Modifier.offset(y = (-0.5).dp)) { SmallOutlined(button, onClick) }
    }
}

/** Tools: a 3 x 2 grid of tiles with page dots under it. Look only for now. */
@Composable
private fun ToolsGrid(onClick: () -> Unit) {
    val tiles = listOf("Buy Back" to Icons.Outlined.Autorenew, "Edit Buy Price" to Icons.Outlined.InsertDriveFile, "Profit & Loss" to Icons.Outlined.BarChart,
        "Tax Report" to Icons.Outlined.Description, "Trades and Charges" to Icons.Outlined.Receipt, "Stock SIP" to Icons.Outlined.Spa)
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(start = 12.dp, end = 12.dp, top = 11.5.dp, bottom = 16.dp).testTag("tools")) {
        Text("ALPHA Tools", fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = titleInk(), modifier = Modifier.padding(start = 4.dp, bottom = 16.dp))
        tiles.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 11.dp), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                row.forEach { (label, icon) ->
                    Column(Modifier.weight(1f).height(84.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Box(contentAlignment = Alignment.Center) { // rupee marks on Buy Back and Edit Buy Price
                            Icon(icon, null, Modifier.size(30.dp), tint = if (label == "Profit & Loss") Accent else IconInk)
                            if (label == "Buy Back" || label == "Edit Buy Price") Text("₹", fontSize = 11.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold, color = IconInk, modifier = Modifier.offset(y = if (label == "Buy Back") 0.dp else 2.dp))
                        }
                        Text(label, fontSize = 13.sp, lineHeight = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.Center) {
            Box(Modifier.size(16.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(Accent)); Spacer(Modifier.width(5.dp))
            Box(Modifier.size(5.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.outline))
        }
    }
}

/**
 * News: a sideways row of cards, one per held stock with its day change. The app has no news source yet, so each card says that
 * headlines will show there; nothing is made up.
 */
@Composable
private fun NewsStrip(movers: List<Pair<String, BigDecimal?>>, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(vertical = 16.dp).testTag("news")) {
        Row(Modifier.padding(horizontal = 16.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
            Text("News", fontSize = 18.sp, fontWeight = FontWeight.Medium); Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(24.dp), tint = Accent)
        }
        val cards = movers.ifEmpty { listOf("" to null) }
        androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 12.dp)) {
            items(cards.size) { i -> val (sym, chg) = cards[i]
                Column(Modifier.width(300.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Market news", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text("COMING SOON", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = CandleGreen, modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(CandleGreen.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (sym.isEmpty()) "News for your stocks" else "News for $sym", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(22.dp))
                    }
                    Text("Headlines about the stocks you hold will show here.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                    if (sym.isNotEmpty()) Row(Modifier.padding(top = 10.dp)) {
                        val c = if ((chg ?: BigDecimal.ZERO).signum() < 0) CandleRed else CandleGreen
                        Text("$sym  " + (chg?.let { Fmt.pct(it) } ?: "—"), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c,
                            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
        }
    }
}

/**
 * One Assets row: invested value and gain on the right, or a short dark line ([emptyText], then [emptyStrong] in Medium) and an
 * outlined button when nothing is held yet.
 */
@Composable
private fun AssetRow(icon: @Composable () -> Unit, title: String, s: AssetSum, has: Boolean, todayView: Boolean, hidden: Boolean,
                     emptyText: String, button: String, onButton: () -> Unit, onOpen: () -> Unit, tag: String, emptyStrong: String = "") {
    // 66 dp either way. With holdings the second line sits 4.5 dp under the title; the empty line sits 2.5 dp under it and the whole
    // block rides a little higher. The icon sits above the middle in both.
    Row(Modifier.fillMaxWidth().clickable(enabled = has, onClick = onOpen).padding(start = 12.dp, end = 12.dp, top = if (has) 16.dp else 15.5.dp, bottom = if (has) 11.5.dp else 14.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.offset(y = if (has) (-3).dp else (-2).dp).size(width = 40.dp, height = 36.dp), contentAlignment = Alignment.Center) { icon() }; Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = titleInk(), maxLines = 1)
            val ink = titleInk()
            Text(if (has) buildAnnotatedString { append("Invested Value "); withStyle(SpanStyle(color = ink)) { append(if (hidden) "₹ ••••" else Fmt.rupee(s.invested, 0)) } }
                else buildAnnotatedString { append(emptyText); withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(emptyStrong) } },
                fontSize = 12.sp, lineHeight = 16.sp, color = if (has) subInk() else ink, maxLines = if (has) 1 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = if (has) 4.5.dp else 2.5.dp))
        }
        if (has) Column(horizontalAlignment = Alignment.End) {
            val v = if (todayView) s.today else s.pnl
            val pnlInk = pnlInk(v, MaterialTheme.colorScheme.onSurface)
            Text(v?.let { if (hidden) "₹ ••••" else signedRupee(it) } ?: "—", fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = pnlInk)
            if (v != null && !todayView) Text(Fmt.pct(s.pct(v)), fontSize = 12.sp, lineHeight = 16.sp, color = pnlInk, modifier = Modifier.padding(top = 4.5.dp).offset(y = 0.5.dp))
        } else { Spacer(Modifier.width(8.dp)); Box(Modifier.offset(y = (-1).dp)) { SmallOutlined(button, onButton) } }
    }
}

/** Mutual Funds tab: one row per fund (name, "units · Avg", P&L, NAV); tapping opens its details. */
@Composable
private fun FundRows(funds: List<com.papertrade.app.data.repo.MutualFundHolding>, onOpen: (com.papertrade.app.data.repo.MutualFundHolding) -> Unit, onAdd: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
        items(funds, key = { "mf_" + it.schemeCode }) { f ->
            Column(Modifier.fillMaxWidth().clickable { onOpen(f) }.testTag("fund_${f.schemeCode}")) {
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
        item(key = "add_fund") { TextButton(onAdd, Modifier.testTag("holdings_add_fund")) { Text("+ Add a mutual fund") } }
    }
}

/**
 * Portfolio, My Wealth page: everything in the app added up. Cash and F&O come from the virtual account (F&O = open futures' unrealised
 * P&L plus options at market), stocks at market value, and mutual funds at the latest NAV.
 */
@Composable
fun MyWealthScreen(vm: AppVM) {
    val acc by vm.account.collectAsState(); val v by vm.valuation.collectAsState(); val funds by vm.mutualFunds.collectAsState()
    val isStock = { id: String -> vm.info(id)?.segment == Segment.EQUITY || vm.info(id) == null && id.endsWith("-EQ") }
    val stocks = v?.positions?.filter { isStock(it.instrumentId) && acc?.positions?.get(it.instrumentId)?.isOpen == true }?.fold(BigDecimal.ZERO) { s, p -> s + p.marketValue } ?: BigDecimal.ZERO
    val fno = (v?.positionsValue ?: BigDecimal.ZERO) - stocks
    val mf = funds.fold(BigDecimal.ZERO) { s, f -> s + f.current }; val cash = v?.cash ?: BigDecimal.ZERO
    val total = cash + stocks + fno + mf
    Column(Modifier.fillMaxSize().background(PageGrey).verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(WealthTop, WealthBottom))).padding(horizontal = 16.dp, vertical = 18.dp).testTag("my_wealth_card")) {
            Text("Total wealth", fontSize = 14.sp, color = Color(0xFFC9D2DE))
            Text(Fmt.rupee(total, 0), fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, color = Color.White, modifier = Modifier.padding(top = 2.dp))
        }
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 8.dp)) {
            listOf("Cash" to cash, "Stocks" to stocks, "Futures & options" to fno, "Mutual funds" to mf).forEach { (label, amt) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    Text(Fmt.rupee(amt), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
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

/** ANALYZE mark: a small bare pulse line (flat, a dip, a spike, flat), as in the reference. */
@Composable
private fun PulseIcon() = androidx.compose.foundation.Canvas(Modifier.size(width = 13.75.dp, height = 12.dp)) {
    val w = size.width; val h = size.height; val sw = 1.6.dp.toPx()
    val pts = listOf(0f to 0.52f, 0.24f to 0.52f, 0.38f to 0.93f, 0.61f to 0.07f, 0.77f to 0.52f, 1f to 0.52f).map { (x, y) -> androidx.compose.ui.geometry.Offset(x * w, y * h) }
    drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }, VerifyCyan,
        style = androidx.compose.ui.graphics.drawscope.Stroke(sw, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

/** Search mark of the Equity box: a thin pale ring with a short handle. */
@Composable
private fun SearchRing() { val c = if (androidx.compose.foundation.isSystemInDarkTheme()) MaterialTheme.colorScheme.onSurfaceVariant else androidx.compose.ui.graphics.Color(0xFFA3A9AE)
    androidx.compose.foundation.Canvas(Modifier.size(width = 14.5.dp, height = 17.dp)) {
        val sw = 1.3.dp.toPx()
        drawCircle(c, 6.55.dp.toPx(), androidx.compose.ui.geometry.Offset(7.2.dp.toPx(), 7.2.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
        drawLine(c, androidx.compose.ui.geometry.Offset(11.7.dp.toPx(), 12.1.dp.toPx()), androidx.compose.ui.geometry.Offset(13.9.dp.toPx(), 16.3.dp.toPx()), sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** TPIN mark: a small purple octagon with a white exclamation mark. */
@Composable
private fun TpinMark() = androidx.compose.foundation.Canvas(Modifier.size(14.5.dp)) {
    val s = size.minDimension; val e = 0.6.dp.toPx(); val a = e + (s - 2 * e) * 0.293f; val f = s - e
    val oct = androidx.compose.ui.graphics.Path().apply { moveTo(a, e); lineTo(s - a, e); lineTo(f, a); lineTo(f, s - a); lineTo(s - a, f); lineTo(a, f); lineTo(e, s - a); lineTo(e, a); close() }
    drawPath(oct, TpinPurple); drawPath(oct, TpinPurple, style = androidx.compose.ui.graphics.drawscope.Stroke(2 * e, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    drawLine(androidx.compose.ui.graphics.Color.White, androidx.compose.ui.geometry.Offset(s / 2, 0.27f * s), androidx.compose.ui.geometry.Offset(s / 2, 0.56f * s), 1.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
    drawCircle(androidx.compose.ui.graphics.Color.White, 0.85.dp.toPx(), androidx.compose.ui.geometry.Offset(s / 2, 0.735f * s))
}

/** The thin dark chevron at the end of the TPIN strip. */
@Composable
private fun Chevron() = androidx.compose.foundation.Canvas(Modifier.offset(y = (-1).dp).size(width = 5.6.dp, height = 10.dp)) {
    val w = size.width; val h = size.height; val sw = 1.4.dp.toPx(); val i = sw / 2
    drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(i, i); lineTo(w - i, h / 2); lineTo(i, h - i) }, androidx.compose.ui.graphics.Color(0xFF1C1828),
        style = androidx.compose.ui.graphics.drawscope.Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

/** Filter mark: three small dark sliders with flat knobs (outer two low, middle high); each line breaks around its knob. */
@Composable
private fun FilterIcon() = androidx.compose.foundation.Canvas(Modifier.size(width = 15.dp, height = 12.5.dp)) {
    val h = size.height; val c = androidx.compose.ui.graphics.Color(0xFF50565C); val lw = 1.2.dp.toPx(); val kw = 3.75.dp.toPx(); val kh = 1.6.dp.toPx(); val gap = 0.9.dp.toPx()
    listOf(1.9.dp.toPx() to 0.68f, 7.5.dp.toPx() to 0.32f, 13.1.dp.toPx() to 0.68f).forEach { (cx, k) ->
        val ky = k * h
        drawLine(c, androidx.compose.ui.geometry.Offset(cx, 0f), androidx.compose.ui.geometry.Offset(cx, ky - kh / 2 - gap), lw)
        drawLine(c, androidx.compose.ui.geometry.Offset(cx, ky + kh / 2 + gap), androidx.compose.ui.geometry.Offset(cx, h), lw)
        drawRect(c, androidx.compose.ui.geometry.Offset(cx - kw / 2, ky - kh / 2), androidx.compose.ui.geometry.Size(kw, kh))
    }
}
