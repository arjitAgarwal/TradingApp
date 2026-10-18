package com.papertrade.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.papertrade.app.AppContainer
import com.papertrade.app.data.market.*
import com.papertrade.app.ui.*
import com.papertrade.app.ui.components.*
import com.papertrade.core.Side
import java.math.BigDecimal

@Composable private fun <T : androidx.lifecycle.ViewModel> scoped(c: AppContainer, key: String, arg: String, cls: Class<T>): T =
    viewModel(cls, key = "$key:$arg", factory = Factory(c).also { it.arg = arg })

@Composable
fun QuoteHeader(q: MarketQuote?, vm: AppVM) {
    val now by vm.now.collectAsState()
    Column {
        Text(Fmt.inr(q?.ltp), fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text("${Fmt.signed(q?.change)}  (${Fmt.pct(q?.changePct)})", color = Fmt.color(q?.change, MaterialTheme.colorScheme.onSurface))
        FreshnessChip(freshnessOf(q, now, vm.maxAgeSec()), q?.let { Fmt.time(it.timestamp) })
        if (q?.source == com.papertrade.core.QuoteSource.CACHED) Text("Previous / cached quote — not a live price. Market orders are disabled.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun ChartSection(c: AppContainer, symbol: String) {
    val vm = scoped(c, "candles", symbol, CandlesVM::class.java)
    val state by vm.state.collectAsState(); val interval by vm.interval.collectAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { CandleInterval.values().forEach { i -> FilterChip(interval == i, { vm.select(i) }, { Text(i.label) }) } }
    Box(Modifier.fillMaxWidth().heightIn(min = 220.dp)) { StateHost(state, vm::load) { PriceChart(it, Modifier.fillMaxWidth()) } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(title: String, nav: Nav, content: @Composable ColumnScope.() -> Unit) = Scaffold(topBar = {
    TopAppBar(title = { Text(title) }, navigationIcon = { TextButton(nav.back) { Text("←") } })
}) { Column(Modifier.padding(it).padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }

@Composable
fun IndexScreen(c: AppContainer, vm: AppVM, u: Underlying, nav: Nav) {
    val q = vm.quotes.collectAsState().value[u.indexSymbol]; val provider by vm.provider.collectAsState()
    DetailScaffold(u.displayName, nav) {
        QuoteHeader(q, vm)
        ChartSection(c, u.indexSymbol)
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
            StatRow("Open", Fmt.inr(q?.open)); StatRow("High", Fmt.inr(q?.high)); StatRow("Low", Fmt.inr(q?.low)); StatRow("Previous close", Fmt.inr(q?.prevClose))
            StatRow("Data provider", provider.displayName); StatRow("Last update", Fmt.time(q?.timestamp))
        } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ nav.chain(u) }, Modifier.weight(1f).testTag("btn_chain")) { Text("Option chain") }
            OutlinedButton({ nav.futures(u) }, Modifier.weight(1f).testTag("btn_futures")) { Text("Futures") }
        }
    }
}

@Composable
fun StockScreen(c: AppContainer, vm: AppVM, symbol: String, nav: Nav) {
    val q = vm.quotes.collectAsState().value[symbol]; val lists by vm.watchlists.collectAsState()
    var info by remember { mutableStateOf(vm.info(symbol)) }; var err by remember { mutableStateOf<String?>(null) }; var ticket by remember { mutableStateOf<Side?>(null) }
    DisposableEffect(symbol) { vm.subscribe("stock", setOf(symbol)); onDispose { vm.unsubscribe("stock") } }
    LaunchedEffect(symbol) { if (info == null) vm.equityInfo(symbol).fold({ info = it; vm.register(it) }, { err = it.message }) }
    DetailScaffold(symbol.substringAfter(':').removeSuffix("-EQ"), nav) {
        info?.let { Text(it.name) }
        QuoteHeader(q, vm); ChartSection(c, symbol)
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { StatRow("Open", Fmt.inr(q?.open)); StatRow("High", Fmt.inr(q?.high)); StatRow("Low", Fmt.inr(q?.low)); StatRow("Previous close", Fmt.inr(q?.prevClose)); StatRow("Bid / Ask", "${Fmt.inr(q?.bid)} / ${Fmt.inr(q?.ask)}") } }
        err?.let { Text("Instrument metadata unavailable: $it", color = MaterialTheme.colorScheme.error) }
        val inList = lists.firstOrNull()?.items?.any { it.symbol == symbol } == true
        lists.firstOrNull()?.let { w -> info?.let { i -> OutlinedButton({ if (inList) vm.removeFromWatchlist(w.id, symbol) else vm.addToWatchlist(w.id, symbol, i.name) }) { Text(if (inList) "Remove from ${w.name}" else "Add to ${w.name}") } } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ ticket = Side.BUY }, Modifier.weight(1f).testTag("btn_buy"), enabled = info != null) { Text("Buy") }
            OutlinedButton({ ticket = Side.SELL }, Modifier.weight(1f).testTag("btn_sell"), enabled = info != null) { Text("Sell") }
        }
    }
    ticket?.let { s -> info?.let { OrderTicket(it, q, vm, s) { ticket = null } } }
}

@Composable
fun ChainScreen(c: AppContainer, vm: AppVM, u: Underlying, nav: Nav) {
    val cv = scoped(c, "chain", u.name, ChainVM::class.java); val ui by cv.ui.collectAsState(); val now by vm.now.collectAsState()
    ChainBody(vm, u, cv, ui, nav, now)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChainBody(vm: AppVM, u: Underlying, cv: ChainVM, ui: ChainUi, nav: Nav, now: java.time.Instant) {
    val chain = ui.chain
    Scaffold(topBar = { TopAppBar(title = { Text("${u.displayName} option chain") }, navigationIcon = { TextButton(nav.back) { Text("←") } }) }) { pad ->
        Column(Modifier.padding(pad)) {
            if (chain == null) { if (ui.error != null) CenterMessage(if (ui.offline) "You're offline" else "Option chain unavailable", ui.error, cv::start) else Loading(); return@Column }
            Column(Modifier.padding(horizontal = 12.dp)) {
                Text("${u.displayName}  ${Fmt.inr(chain.underlyingLtp)}", fontWeight = FontWeight.Bold)
                val f = if (chain.source == com.papertrade.core.QuoteSource.LIVE) freshnessOf(chain.rows.firstNotNullOfOrNull { it.call?.quote ?: it.put?.quote }, now, vm.maxAgeSec()) else when (chain.source) {
                    com.papertrade.core.QuoteSource.DEMO -> Freshness.DEMO; else -> Freshness.CACHED }
                FreshnessChip(f, Fmt.time(chain.asOf))
                ui.error?.let { Text("Update failed: $it (showing last data)", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    chain.expiries.forEach { e -> FilterChip(e == chain.selectedExpiry, { cv.selectExpiry(e) }, { Text(e.toString()) }) } }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("Call LTP (%)", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Text("Strike", Modifier.width(80.dp), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("Put LTP (%)", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            val atm = chain.underlyingLtp?.let { ltp -> chain.rows.minByOrNull { (it.strike - ltp).abs() }?.strike }
            LazyColumn(Modifier.testTag("chain_table")) {
                items(chain.rows, key = { it.strike.toPlainString() }) { r ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Cell(r.call, Modifier.weight(1f), nav, Alignment.Start)
                        Text(Fmt.inr(r.strike, 0), Modifier.width(80.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = if (r.strike == atm) FontWeight.ExtraBold else FontWeight.Medium,
                            color = if (r.strike == atm) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Cell(r.put, Modifier.weight(1f), nav, Alignment.End)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun Cell(s: OptionSide?, m: Modifier, nav: Nav, align: Alignment.Horizontal) {
    Column(m.clickable(enabled = s != null) { s?.let { nav.contract(it.symbol) } }.padding(vertical = 6.dp), horizontalAlignment = align) {
        Text(Fmt.inr(s?.quote?.ltp), fontWeight = FontWeight.SemiBold)
        Text(Fmt.pct(s?.quote?.changePct), fontSize = 11.sp, color = Fmt.color(s?.quote?.changePct, MaterialTheme.colorScheme.onSurface))
    }
}

@Composable
fun FuturesScreen(c: AppContainer, vm: AppVM, u: Underlying, nav: Nav) {
    val fv = scoped(c, "futures", u.name, FuturesVM::class.java); val state by fv.state.collectAsState(); val quotes by vm.quotes.collectAsState()
    DetailScaffold("${u.displayName} futures", nav) {
        Text("Futures prices differ from the spot index (basis).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.fillMaxWidth().heightIn(min = 200.dp)) { StateHost(state, fv::load) { list ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { list.forEach { f -> val q = quotes[f.info.symbol] ?: f.quote
                Card(Modifier.fillMaxWidth().clickable { nav.contract(f.info.symbol) }.testTag("fut_${f.info.symbol}")) { Column(Modifier.padding(12.dp)) {
                    Text("${u.displayName} FUT  ${f.info.expiry}", fontWeight = FontWeight.SemiBold)
                    Text(Fmt.inr(q?.ltp), fontWeight = FontWeight.Bold); Text("${Fmt.signed(q?.change)} (${Fmt.pct(q?.changePct)})", color = Fmt.color(q?.change, MaterialTheme.colorScheme.onSurface))
                    Text("Lot size ${f.info.lotSize}", fontSize = 12.sp) } } } }
        } }
    }
}

@Composable
fun ContractScreen(vm: AppVM, symbol: String, nav: Nav) {
    val info = vm.info(symbol); val q = vm.quotes.collectAsState().value[symbol]; var ticket by remember { mutableStateOf<Side?>(null) }
    DisposableEffect(symbol) { vm.subscribe("contract", setOf(symbol)); onDispose { vm.unsubscribe("contract") } }
    DetailScaffold(info?.let { "${it.underlying ?: ""} ${it.strike?.let { s -> Fmt.inr(s, 0) } ?: "FUT"} ${it.optionType ?: ""}" } ?: symbol, nav) {
        if (info == null) { Text("Contract metadata (lot size, tick size) is unavailable from the provider, so this contract can't be traded in the simulation.", color = MaterialTheme.colorScheme.error); return@DetailScaffold }
        QuoteHeader(q, vm)
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
            StatRow("Symbol", info.symbol); StatRow("Underlying", info.underlying ?: "—"); StatRow("Expiry", info.expiry?.toString() ?: "—")
            StatRow("Lot size", info.lotSize.toString()); StatRow("Tick size", info.tickSize.toPlainString())
            StatRow("Bid", Fmt.inr(q?.bid)); StatRow("Ask", Fmt.inr(q?.ask)); StatRow("Volume", Fmt.num(q?.volume)); StatRow("Open interest", Fmt.num(q?.openInterest))
            StatRow("Implied volatility", q?.impliedVol?.let { Fmt.inr(it) + "%" } ?: "—")
        } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ ticket = Side.BUY }, Modifier.weight(1f).testTag("btn_buy")) { Text("Buy") }
            OutlinedButton({ ticket = Side.SELL }, Modifier.weight(1f).testTag("btn_sell")) { Text("Sell") }
        }
    }
    ticket?.let { s -> info?.let { OrderTicket(it, q, vm, s) { ticket = null } } }
}
