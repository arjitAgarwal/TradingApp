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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
        item { Text("Simulated orders only", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
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

/**
 * Groww-style positions: a "Total returns" card with Exit all / Set Safe Exit, then one flat row per F&O position.
 * Closed (booked) positions stay in the list, greyed. Tapping a row opens Exit / Add more.
 */
@Composable
fun PositionsScreen(vm: AppVM, nav: Nav) {
    val acc by vm.account.collectAsState(); val val_ by vm.valuation.collectAsState(); val quotes by vm.quotes.collectAsState()
    val positions = acc?.positions?.values?.filter { p -> vm.info(p.instrumentId)?.segment != Segment.EQUITY && !p.instrumentId.endsWith("-EQ") }
        ?.sortedByDescending { it.isOpen } ?: emptyList()
    var sel by remember { mutableStateOf<Position?>(null) }; var confirmExitAll by remember { mutableStateOf(false) }
    if (positions.isEmpty()) { EmptyState(Icons.Outlined.Insights, "No positions", "Futures and options you buy show up here."); return }
    val pnl = positions.associate { p -> p.instrumentId to (val_?.positions?.firstOrNull { it.instrumentId == p.instrumentId }?.netPnl ?: (p.realizedGross - p.charges)) }
    val open = positions.filter { it.isOpen }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            val total = pnl.values.fold(BigDecimal.ZERO, BigDecimal::add)
            OutlinedCard(Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text("Total returns", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text((if (total.signum() > 0) "+" else "") + Fmt.rupee(total), fontSize = 20.sp, fontWeight = FontWeight.Medium,
                        color = Fmt.color(total, MaterialTheme.colorScheme.onSurface), modifier = Modifier.padding(top = 2.dp).testTag("positions_total"))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.clickable(enabled = open.isNotEmpty()) { confirmExitAll = true }.testTag("exit_all"), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.Logout, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Exit all", fontSize = 15.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    // Safe Exit (auto-exit at a total profit/loss limit) isn't built yet.
                    Row(Modifier.clickable { vm.messages.tryEmit("Set Safe Exit is coming soon") }, verticalAlignment = Alignment.CenterVertically) {
                        Text("Set Safe Exit", fontSize = 14.sp, modifier = Modifier.dashedUnderline(MaterialTheme.colorScheme.outline))
                        Spacer(Modifier.width(6.dp)); Icon(Icons.Filled.VerifiedUser, null, Modifier.size(16.dp), tint = Gain)
                    }
                }
            }
        }
        items(positions, key = { it.instrumentId }) { p -> PositionRow(vm, p, pnl.getValue(p.instrumentId), quotes[p.instrumentId]?.ltp) { sel = p } }
    }
    sel?.let { p0 -> val p = acc?.positions?.get(p0.instrumentId) ?: p0
        PositionSheet(vm, p, pnl[p.instrumentId] ?: BigDecimal.ZERO, onClose = { sel = null },
            onExit = { sel = null; vm.closePosition(p) },
            onAddMore = { sel = null; nav.order(p.instrumentId, if (p.quantity < 0) Side.SELL else Side.BUY) })
    }
    if (confirmExitAll) AlertDialog(onDismissRequest = { confirmExitAll = false }, title = { Text("Exit all positions?") },
        text = { Text("${open.size} open ${if (open.size == 1) "position" else "positions"} will be closed at market price.") },
        confirmButton = { TextButton({ confirmExitAll = false; open.forEach(vm::closePosition) }, Modifier.testTag("confirm_exit_all")) { Text("Exit all") } },
        dismissButton = { TextButton({ confirmExitAll = false }) { Text("Cancel") } })
}

/** "Delivery" / name / "Mkt ₹x" on the left; signed quantity / P&L / "Avg ₹y" on the right. Booked rows are greyed. */
@Composable
private fun PositionRow(vm: AppVM, p: Position, pnl: BigDecimal, ltp: BigDecimal?, onClick: () -> Unit) {
    val info = vm.info(p.instrumentId); val units = p.quantity * (info?.lotSize ?: 1)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).alpha(if (p.isOpen) 1f else 0.55f).testTag("pos_${p.instrumentId}")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (p.isOpen) "Delivery" else "Delivery · Closed", fontSize = 13.sp, color = dim)
                Text(Fmt.label(p.instrumentId, info), fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Mkt ${Fmt.rupee(ltp)}", fontSize = 13.sp, color = dim)            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text((if (units > 0) "+" else "") + units, fontSize = 13.sp, color = dim)
                Text((if (pnl.signum() > 0) "+" else "") + Fmt.rupee(pnl), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Fmt.color(pnl, MaterialTheme.colorScheme.onSurface))
                Text("Avg ${Fmt.rupee(p.avgPrice.takeIf { p.isOpen })}", fontSize = 13.sp, color = dim)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Tap on a position: its details, then Exit (close at market) and Add more (order page, same side). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PositionSheet(vm: AppVM, p: Position, pnl: BigDecimal, onClose: () -> Unit, onExit: () -> Unit, onAddMore: () -> Unit) {
    val info = vm.info(p.instrumentId)
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).testTag("position_sheet")) {
            Text(Fmt.label(p.instrumentId, info), fontSize = 17.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            StatRow("Quantity", "${p.quantity * (info?.lotSize ?: 1)} (${p.quantity} ${if (kotlin.math.abs(p.quantity) == 1) "lot" else "lots"})")
            if (p.isOpen) StatRow("Avg price", Fmt.rupee(p.avgPrice))
            StatRow("Charges", Fmt.rupee(p.charges))
            StatRow("Returns", (if (pnl.signum() > 0) "+" else "") + Fmt.rupee(pnl), Fmt.color(pnl, MaterialTheme.colorScheme.onSurface))
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (p.isOpen) OutlinedButton(onExit, Modifier.weight(1f).height(52.dp).testTag("pos_exit"), shape = RoundedCornerShape(8.dp)) { Text("Exit", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                Button(onAddMore, Modifier.weight(1f).height(52.dp).testTag("pos_add_more"), enabled = info != null, shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Gain, contentColor = androidx.compose.ui.graphics.Color.White)) { Text(if (p.isOpen) "Add more" else "Buy again", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
fun HoldingsScreen(vm: AppVM, nav: Nav) {
    val acc by vm.account.collectAsState(); val v by vm.valuation.collectAsState(); val quotes by vm.quotes.collectAsState()
    val holdings = acc?.positions?.values?.filter { vm.info(it.instrumentId)?.segment == Segment.EQUITY || vm.info(it.instrumentId) == null && it.instrumentId.endsWith("-EQ") }?.filter { it.isOpen || it.realizedGross.signum() != 0 } ?: emptyList()
    if (holdings.isEmpty()) { EmptyState(Icons.Outlined.Inventory2, "No holdings", "Stocks you buy for delivery show up here."); return }
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp)) {
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(vm: AppVM, nav: Nav) {
    val acc by vm.account.collectAsState(); val v by vm.valuation.collectAsState(); val settings by vm.settings.collectAsState(); val ctx: Context = LocalContext.current
    val auth by vm.fyersAuth.collectAsState(); val upstoxAuth by vm.upstoxAuth.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri?.let { vm.export { text -> ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(text.toByteArray()) } } } }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.import(ctx.contentResolver.openInputStream(it)?.bufferedReader()?.readText() ?: "") } }
    Scaffold(topBar = { TopAppBar(title = { Text("Account & settings") }, navigationIcon = { TextButton(nav.back) { Text("←") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Text("Virtual wallet (simulation only)", fontWeight = FontWeight.SemiBold)
                StatRow("Starting capital", Fmt.rupee(acc?.wallet?.startingCapital)); StatRow("Available cash", Fmt.rupee(v?.availableCash)); StatRow("Reserved margin (simulated)", Fmt.rupee(v?.reservedMargin))
                StatRow("Realized P&L", Fmt.signed(v?.realizedGross)); StatRow("Unrealized P&L", Fmt.signed(v?.unrealized)); StatRow("Simulated charges", Fmt.rupee(v?.totalCharges))
                StatRow("Net P&L", Fmt.signed(v?.netPnl), Fmt.color(v?.netPnl, MaterialTheme.colorScheme.onSurface)); StatRow("Total equity", Fmt.rupee(v?.equity), Color.Unspecified)
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
            } }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Backup", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ exportLauncher.launch("papertrade-export.json") }) { Text("Export") }; OutlinedButton({ importLauncher.launch(arrayOf("application/json", "text/plain")) }) { Text("Import") } }
                Button({ confirmReset = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Reset simulation to ₹13,54,510") }
            } }
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text("Reset simulation?") }, text = { Text("All simulated orders, trades and positions will be deleted. Export first if you want a backup.") },
        confirmButton = { TextButton({ vm.reset(); confirmReset = false }) { Text("Reset") } }, dismissButton = { TextButton({ confirmReset = false }) { Text("Cancel") } })
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
