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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papertrade.app.data.market.DataMode
import com.papertrade.app.data.repo.AppSettings
import com.papertrade.app.data.repo.FyersAuthState
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

@Composable
fun PositionsScreen(vm: AppVM) {
    val acc by vm.account.collectAsState(); val val_ by vm.valuation.collectAsState()
    val rows = val_?.positions?.filter { r -> acc?.positions?.get(r.instrumentId)?.let { p -> vm.info(p.instrumentId)?.segment != Segment.EQUITY && p.isOpen } == true } ?: emptyList()
    if (rows.isEmpty()) { EmptyState(Icons.Outlined.Insights, "No open positions", "Futures and options you buy show up here."); return }
    Column(Modifier.fillMaxSize()) {
        Card(Modifier.fillMaxWidth().padding(12.dp)) { Column(Modifier.padding(12.dp)) {
            Text("Total P&L (positions)"); val total = rows.fold(BigDecimal.ZERO) { a, r -> a + r.netPnl }
            Text(Fmt.rupee(total), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Fmt.color(total, MaterialTheme.colorScheme.onSurface), modifier = Modifier.testTag("positions_total")) } }
        LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.instrumentId }) { r -> val p = acc!!.positions.getValue(r.instrumentId); val q = vm.quotes.collectAsState().value[p.instrumentId]
                Card(Modifier.fillMaxWidth().testTag("pos_${p.instrumentId}")) { Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(name(vm, p.instrumentId), fontWeight = FontWeight.SemiBold); Text(if (p.isOpen) (if (p.quantity > 0) "LONG" else "SHORT") else "CLOSED") }
                    StatRow("Quantity (lots)", p.quantity.toString()); StatRow("Avg entry", Fmt.inr(p.avgPrice)); StatRow("Current", Fmt.inr(q?.ltp))
                    StatRow("Realized", Fmt.signed(r.realizedGross), Fmt.color(r.realizedGross, MaterialTheme.colorScheme.onSurface)); StatRow("Unrealized", Fmt.signed(r.unrealized), Fmt.color(r.unrealized, MaterialTheme.colorScheme.onSurface))
                    StatRow("Charges", Fmt.rupee(r.charges)); StatRow("Net P&L", Fmt.signed(r.netPnl), Fmt.color(r.netPnl, MaterialTheme.colorScheme.onSurface))
                    if (p.isOpen) OutlinedButton({ vm.closePosition(p) }, Modifier.testTag("close_${p.instrumentId}")) { Text("Close position (market)") }
                } } }
        }
    }
}

@Composable
fun HoldingsScreen(vm: AppVM, nav: Nav) {
    val acc by vm.account.collectAsState(); val v by vm.valuation.collectAsState(); val quotes by vm.quotes.collectAsState()
    val holdings = acc?.positions?.values?.filter { vm.info(it.instrumentId)?.segment == Segment.EQUITY || vm.info(it.instrumentId) == null && it.instrumentId.endsWith("-EQ") }?.filter { it.isOpen || it.realizedGross.signum() != 0 } ?: emptyList()
    if (holdings.isEmpty()) { CenterMessage("No holdings", "Buy a stock from Explore to create a delivery holding."); return }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(holdings, key = { it.instrumentId }) { p -> val r = v?.positions?.firstOrNull { it.instrumentId == p.instrumentId }; val q = quotes[p.instrumentId]
            Card(Modifier.fillMaxWidth().clickable { nav.stock(p.instrumentId) }) { Column(Modifier.padding(12.dp)) {
                Text(p.instrumentId.substringAfter(':').removeSuffix("-EQ") + (vm.info(p.instrumentId)?.let { " · ${it.name}" } ?: ""), fontWeight = FontWeight.SemiBold)
                StatRow("Quantity", p.quantity.toString()); StatRow("Avg buy price", Fmt.inr(p.avgPrice)); StatRow("Market price", Fmt.inr(q?.ltp))
                StatRow("Invested", Fmt.rupee(p.avgPrice * BigDecimal(p.quantity))); StatRow("Market value", Fmt.rupee(r?.marketValue))
                StatRow("Realized P&L", Fmt.signed(p.realizedGross), Fmt.color(p.realizedGross, MaterialTheme.colorScheme.onSurface)); StatRow("Unrealized P&L", Fmt.signed(r?.unrealized), Fmt.color(r?.unrealized, MaterialTheme.colorScheme.onSurface))
            } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(vm: AppVM, nav: Nav) {
    val acc by vm.account.collectAsState(); val v by vm.valuation.collectAsState(); val settings by vm.settings.collectAsState(); val ctx: Context = LocalContext.current
    val auth by vm.fyersAuth.collectAsState()
    var appId by remember { mutableStateOf("") }; var secret by remember { mutableStateOf("") }; var redirect by remember { mutableStateOf("") }
    var showSetup by remember { mutableStateOf(false) }; var confirmReset by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri?.let { vm.export { text -> ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(text.toByteArray()) } } } }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { vm.import(ctx.contentResolver.openInputStream(it)?.bufferedReader()?.readText() ?: "") } }
    Scaffold(topBar = { TopAppBar(title = { Text("Account & settings") }, navigationIcon = { TextButton(nav.back) { Text("←") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card { Column(Modifier.padding(12.dp)) {
                Text("Virtual wallet (simulation only)", fontWeight = FontWeight.SemiBold)
                StatRow("Starting capital", Fmt.rupee(acc?.wallet?.startingCapital)); StatRow("Available cash", Fmt.rupee(v?.availableCash)); StatRow("Reserved margin (simulated)", Fmt.rupee(v?.reservedMargin))
                StatRow("Realized P&L", Fmt.signed(v?.realizedGross)); StatRow("Unrealized P&L", Fmt.signed(v?.unrealized)); StatRow("Simulated charges", Fmt.rupee(v?.totalCharges))
                StatRow("Net P&L", Fmt.signed(v?.netPnl), Fmt.color(v?.netPnl, MaterialTheme.colorScheme.onSurface)); StatRow("Total equity", Fmt.rupee(v?.equity), Color.Unspecified)
            } }
            Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Market data", fontWeight = FontWeight.SemiBold)
                Row { listOf(DataMode.DEMO to "Demo (synthetic)", DataMode.LIVE_PROVIDER to "FYERS (real)").forEach { (m, l) -> FilterChip(settings.dataMode == m, { vm.saveSettings(settings.copy(dataMode = m)) }, { Text(l) }, modifier = Modifier.testTag("mode_${m.name}")) } }
                Text(when (auth) {
                    FyersAuthState.READY -> "FYERS: logged in. Renews itself every day" + (vm.fyersLoginValidUntil()?.let { "; next login needed around ${Fmt.time(it)}" } ?: "") + "."
                    FyersAuthState.NEEDS_LOGIN -> "FYERS: not logged in (or the 15-day login ran out). Tap Log in."
                    FyersAuthState.NOT_SET_UP -> "FYERS: app details not set up yet (one-time, see Setup below)."
                }, fontSize = 12.sp, modifier = Modifier.testTag("fyers_status"))
                Row { Button(nav.fyersLogin, enabled = auth != FyersAuthState.NOT_SET_UP, modifier = Modifier.testTag("fyers_login")) { Text("Log in to FYERS") }; Spacer(Modifier.width(8.dp)); OutlinedButton({ vm.logoutFyers() }, enabled = auth == FyersAuthState.READY) { Text("Log out") } }
                TextButton({ showSetup = !showSetup }) { Text(if (showSetup) "Hide setup" else "Setup (app owner, one time)") }
                if (showSetup) {
                    Text("From https://myapi.fyers.in/dashboard/. Only a hash of the secret is stored. The redirect URI must exactly match the one registered for the FYERS app.", fontSize = 12.sp)
                    OutlinedTextField(appId, { appId = it }, label = { Text("FYERS app id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(secret, { secret = it }, label = { Text("App secret") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(redirect, { redirect = it }, label = { Text("Redirect URI") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Button({ vm.setupFyers(appId, secret, redirect); secret = ""; showSetup = false }, enabled = appId.isNotBlank() && secret.isNotBlank() && redirect.isNotBlank()) { Text("Save setup") }
                }
            } }
            Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Simulation rules", fontWeight = FontWeight.SemiBold)
                IntField("Max quote age (seconds)", settings.maxQuoteAgeSec) { vm.saveSettings(settings.copy(maxQuoteAgeSec = it.coerceIn(1, 3600))) }
                IntField("LTP-only slippage (bps)", settings.slippageBps) { vm.saveSettings(settings.copy(slippageBps = it.coerceIn(0, 1000))) }
                IntField("Partial-fill cap per tick (0 = off)", settings.partialFillCap) { vm.saveSettings(settings.copy(partialFillCap = it.coerceAtLeast(0))) }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Switch(settings.chargesEnabled, { vm.saveSettings(settings.copy(chargesEnabled = it)) }); Text("  Apply illustrative charges") }
                Text("Charge rates and margin percentages are illustrative assumptions, not official broker/exchange figures.", fontSize = 11.sp)
            } }
            Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Backup", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton({ exportLauncher.launch("papertrade-export.json") }) { Text("Export") }; OutlinedButton({ importLauncher.launch(arrayOf("application/json", "text/plain")) }) { Text("Import") } }
                Button({ confirmReset = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Reset simulation to ₹10,00,000") }
            } }
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false }, title = { Text("Reset simulation?") }, text = { Text("All simulated orders, trades and positions will be deleted. Export first if you want a backup.") },
        confirmButton = { TextButton({ vm.reset(); confirmReset = false }) { Text("Reset") } }, dismissButton = { TextButton({ confirmReset = false }) { Text("Cancel") } })
}

@Composable
private fun IntField(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(text, { text = it.filter(Char::isDigit); text.toIntOrNull()?.let(onChange) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}
private typealias Color = androidx.compose.ui.graphics.Color
