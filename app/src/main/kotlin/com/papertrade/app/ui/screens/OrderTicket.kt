package com.papertrade.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papertrade.app.data.market.InstrumentInfo
import com.papertrade.app.data.market.MarketDepth
import com.papertrade.app.ui.AppVM
import com.papertrade.app.ui.Fmt
import com.papertrade.app.ui.theme.Accent
import com.papertrade.app.ui.theme.Warn
import com.papertrade.core.*
import java.math.BigDecimal
import java.time.LocalDate

/**
 * SIMULATED order page. Nothing here can reach a broker.
 * Quantity is entered in lots for F&O (shares for stocks) and the engine is sent lots. The number pad is the app's own,
 * so the phone keyboard never covers the page. Carry forward / Intraday and Smart Orders are look-only for now.
 */
@Composable
fun OrderScreen(vm: AppVM, symbol: String, side: Side, nav: Nav) {
    val info = vm.info(symbol)
    if (info == null) {
        DetailScaffold("Order", nav) { Text("Contract details (lot size, tick size) are unavailable, so this can't be traded right now.", color = MaterialTheme.colorScheme.error) }
        return
    }
    DisposableEffect(symbol) { vm.subscribe("order", setOf(symbol)); onDispose { vm.unsubscribe("order") } }
    OrderPage(info, vm, side, nav.back)
}

private enum class Field { LOTS, PRICE }
private val PageGrey = Color(0xFFF2F4F7); private val BuyGreen = Color(0xFF00916E); private val SellRed = Color(0xFFD64F4F)
private val SoftGreen = Color(0xFFDDF1EC); private val DarkGreen = Color(0xFF0B7A5C)

@Composable
private fun OrderPage(info: InstrumentInfo, vm: AppVM, side: Side, onClose: () -> Unit) {
    val q = vm.quotes.collectAsState().value[info.symbol]
    val testMode = vm.settings.collectAsState().value.testClosedMarket
    val valuation by vm.valuation.collectAsState()
    val fno = info.segment != Segment.EQUITY
    val lot = if (fno) info.lotSize else 1
    var type by remember { mutableStateOf(OrderType.LIMIT) }
    var lotsText by remember { mutableStateOf("1") }
    var limitText by remember { mutableStateOf(q?.ltp?.toPlainString() ?: "") }
    LaunchedEffect(q != null) { if (limitText.isEmpty()) q?.ltp?.let { limitText = it.toPlainString() } } // the price may arrive after the page opens
    var carryForward by remember { mutableStateOf(true) }
    var focus by remember { mutableStateOf(Field.LOTS) }; var keypad by remember { mutableStateOf(true) }
    var bestFive by remember { mutableStateOf(false) }
    var rejected by remember { mutableStateOf<String?>(null) }
    var placing by remember { mutableStateOf(false) }

    val lots = lotsText.toIntOrNull() ?: 0
    val shares = lots * lot
    val lp = limitText.toBigDecimalOrNull()
    val ref = if (type == OrderType.LIMIT) lp else (if (side == Side.BUY) q?.ask else q?.bid) ?: q?.ltp
    val notional = ref?.let { it * BigDecimal(shares) }
    val required = when {
        info.segment == Segment.FUTURES -> notional?.let { it * MarginModel().futuresPct.movePointLeft(2) }
        info.segment == Segment.OPTION && side == Side.SELL -> info.strike?.let { it * BigDecimal(shares) * MarginModel().shortOptionPct.movePointLeft(2) }
        else -> notional
    }
    val problem = rejected ?: when {
        info.expiry != null && info.expiry < LocalDate.now(MarketHours.IST) -> "Trading is not allowed in expired contracts."
        lots <= 0 -> if (fno) "Enter the number of lots." else "Enter a quantity."
        type == OrderType.MARKET && q == null -> "No live price right now. Use a limit price."
        type == OrderType.MARKET && q?.source == QuoteSource.CACHED && !testMode -> "Market is closed. Market orders are off; use a limit price."
        type == OrderType.LIMIT && (lp == null || lp.signum() <= 0) -> "Enter a limit price."
        else -> null
    }
    // Keys go to whichever box was tapped last; lots take whole numbers, the price up to two decimals.
    fun key(k: String) {
        rejected = null
        when (focus) {
            Field.LOTS -> lotsText = when (k) { "<" -> lotsText.dropLast(1); "." -> lotsText; else -> (lotsText + k).trimStart('0').take(6) }
            Field.PRICE -> limitText = when (k) {
                "<" -> limitText.dropLast(1)
                "." -> if ('.' in limitText) limitText else (limitText.ifEmpty { "0" } + ".")
                else -> if (limitText.substringAfter('.', "").length >= 2 && '.' in limitText) limitText else (limitText + k).take(10)
            }
        }
    }
    fun stepPrice(up: Boolean) {
        rejected = null; type = OrderType.LIMIT
        val p = (lp ?: q?.ltp ?: BigDecimal.ZERO) + if (up) info.tickSize else info.tickSize.negate()
        limitText = p.coerceAtLeast(info.tickSize).stripTrailingZeros().toPlainString()
    }

    Scaffold(containerColor = PageGrey) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().testTag("order_ticket")) {
            OrderHeader(info, q, onClose)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                Spacer(Modifier.height(10.dp))
                // Product: look-only, the simulation treats both the same.
                Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(4.dp)) {
                    listOf(true to "CARRY FORWARD", false to "INTRADAY").forEach { (v, t) ->
                        Box(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(8.dp)).background(if (carryForward == v) SoftGreen else Color.Transparent)
                            .clickable { carryForward = v }.testTag("product_$t"), contentAlignment = Alignment.Center) {
                            Text(t, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (carryForward == v) DarkGreen else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 16.dp)) {
                    Text(if (fno) "No. of Lots" else "Quantity", fontSize = 15.sp, modifier = Modifier.padding(start = 4.dp))
                    Row(Modifier.padding(top = 10.dp).fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp))
                        .border(1.dp, if (focus == Field.LOTS && keypad) DarkGreen else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                        .clickable { focus = Field.LOTS; keypad = true }.padding(horizontal = 14.dp).testTag("qty"), verticalAlignment = Alignment.CenterVertically) {
                        EntryText(lotsText, focus == Field.LOTS && keypad, Modifier.weight(1f))
                        if (fno) {
                            Text("1 Lot Size = ", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("$lot", fontSize = 14.sp)
                            Icon(Icons.Outlined.Info, "Lot size", Modifier.padding(start = 6.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (fno && lots > 0) Text("$lots × $lot = $shares qty", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                    Text(if (type == OrderType.LIMIT) "Limit Price" else "Price", fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp))
                    Row(Modifier.padding(top = 10.dp).fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                        StepButton(Icons.Default.Remove, "Lower price", "price_minus") { stepPrice(false) }
                        Box(Modifier.padding(horizontal = 4.dp).width(80.dp).fillMaxHeight().clip(RoundedCornerShape(4.dp))
                            .border(1.dp, if (focus == Field.PRICE && keypad && type == OrderType.LIMIT) DarkGreen else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp))
                            .background(if (type == OrderType.MARKET) PageGrey else Color.White)
                            .clickable { type = OrderType.LIMIT; focus = Field.PRICE; keypad = true; rejected = null }.testTag("limit"), contentAlignment = Alignment.Center) {
                            if (type == OrderType.LIMIT) EntryText(limitText, focus == Field.PRICE && keypad)
                            else Text("Market", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StepButton(Icons.Default.Add, "Raise price", "price_plus") { stepPrice(true) }
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(PageGrey)) {
                            listOf(OrderType.LIMIT, OrderType.MARKET).forEach { t ->
                                Box(Modifier.fillMaxHeight().background(if (type == t) BuyGreen else Color.Transparent).clickable {
                                    rejected = null; type = t; if (t == OrderType.LIMIT) { focus = Field.PRICE; if (limitText.isEmpty()) q?.ltp?.let { limitText = it.toPlainString() } } else focus = Field.LOTS
                                }.padding(horizontal = 14.dp).testTag("type_${t.name}"), contentAlignment = Alignment.Center) {
                                    Text(t.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (type == t) Color.White else MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                // Stop loss / GTT aren't built yet; the row is here so the page matches the target layout.
                Row(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(10.dp)).clickable { vm.messages.tryEmit("Stop loss and GTT orders are coming soon") }
                    .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Smart Orders", fontSize = 15.sp, fontWeight = FontWeight.Medium); Text("  (STOP LOSS & GTT)", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f)); Icon(Icons.Default.KeyboardArrowDown, null)
                }
                // Test mode is on and the price isn't live: say so, so a test fill is never mistaken for a real-market one.
                if (testMode && q?.source == QuoteSource.CACHED) Text("Test mode: market is closed, so this fills at the last price.", color = Warn, textAlign = TextAlign.Center,
                    fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("test_mode_note"))
                problem?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(10.dp)).padding(10.dp).testTag("order_problem"))
                }
                Spacer(Modifier.height(12.dp))
            }
            // Footer: approximate requirement, virtual available cash, one place-order button.
            Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("Margin Required (Approx)", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Fmt.rupee(required ?: BigDecimal.ZERO), fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Available Margin", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(Fmt.rupee(valuation?.availableCash), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Icon(Icons.Outlined.Info, "Virtual money", Modifier.padding(start = 6.dp).size(20.dp).clickable { vm.messages.tryEmit("Virtual money for paper trading; no real funds are used") })
                        }
                    }
                }
                Button({
                    placing = true
                    vm.placeOrder(info, side, type, lots, if (type == OrderType.LIMIT) lp else null) { r -> placing = false; if (r.error == null) onClose() else rejected = r.error }
                }, Modifier.padding(top = 10.dp).fillMaxWidth().height(46.dp).testTag("submit_order"), enabled = problem == null && !placing, shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (side == Side.BUY) BuyGreen else SellRed, contentColor = Color.White)) {
                    Text(if (side == Side.BUY) "PLACE BUY ORDER" else "PLACE SELL ORDER", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (keypad) NumberPad(onKey = ::key, onBestFive = { bestFive = true }, onHide = { keypad = false })
        }
    }
    if (bestFive) BestFiveDialog(vm, info.symbol, q) { bestFive = false }
}

/** Back arrow, "NIFTY [NSE FO]" over the contract, and the price with ▲/▼ and the day's change on the right. */
@Composable
private fun OrderHeader(info: InstrumentInfo, q: com.papertrade.app.data.market.MarketQuote?, onClose: () -> Unit) {
    val exchange = info.symbol.substringBefore(':').takeIf { it != "DEMO" } ?: if (info.underlying == "SENSEX") "BSE" else "NSE"
    val name = info.underlying ?: info.symbol.substringAfter(':').removeSuffix("-EQ")
    val contract = info.expiry?.let { e -> "${e.format(java.time.format.DateTimeFormatter.ofPattern("dd-MMM-yy", java.util.Locale.ENGLISH)).uppercase()} " +
        (info.optionType?.let { "${info.strike?.stripTrailingZeros()?.toPlainString() ?: ""} ${it.name}" } ?: "FUT") }
    val c = Fmt.color(q?.change, MaterialTheme.colorScheme.onSurface)
    Row(Modifier.fillMaxWidth().background(Color.White).padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", Modifier.size(26.dp)) }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontSize = 18.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(if (info.segment == Segment.EQUITY) exchange else "$exchange FO", fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp)
                    .background(PageGrey, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
            }
            contract?.let { Text(it, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp)) }
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Fmt.rupee(q?.ltp), fontSize = 18.sp, fontWeight = FontWeight.Medium, color = c)
                q?.change?.takeIf { it.signum() != 0 }?.let { Text(if (it.signum() > 0) "▲" else "▼", fontSize = 12.sp, color = c, modifier = Modifier.padding(start = 3.dp)) }
            }
            Text("${Fmt.signed(q?.change)} (${Fmt.pct(q?.changePct)})", fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** The typed value with a green cursor bar while that box is taking keys. */
@Composable
private fun EntryText(value: String, active: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(value, fontSize = 17.sp, maxLines = 1)
        if (active) Box(Modifier.padding(start = 1.dp).width(2.dp).height(22.dp).background(DarkGreen))
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tag: String, onClick: () -> Unit) {
    Box(Modifier.width(36.dp).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(SoftGreen).clickable(onClick = onClick).testTag(tag), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = DarkGreen)
    }
}

/** The page's own number pad: 1–9, ".", 0 and backspace, with BEST 5 (market depth) and hide on the left. */
@Composable
private fun NumberPad(onKey: (String) -> Unit, onBestFive: () -> Unit, onHide: () -> Unit) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().background(Color.White).height(176.dp).testTag("number_pad")) {
        Column(Modifier.width(60.dp).fillMaxHeight().drawBehind { drawLine(line, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx()) }) {
            Spacer(Modifier.weight(1f))
            Column(Modifier.align(Alignment.CenterHorizontally).clickable(onClick = onBestFive).padding(6.dp).testTag("best_five"), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("BEST", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Accent)
                Box(Modifier.size(width = 18.dp, height = 22.dp).background(Accent, RoundedCornerShape(bottomStart = 9.dp, bottomEnd = 9.dp)), contentAlignment = Alignment.Center) {
                    Text("5", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.padding(8.dp).fillMaxWidth().height(32.dp).clip(RoundedCornerShape(6.dp)).background(PageGrey).clickable(onClick = onHide).testTag("hide_pad"), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.KeyboardDoubleArrowDown, "Hide number pad")
            }
        }
        Column(Modifier.weight(1f)) {
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf(".", "0", "<")).forEach { row ->
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    row.forEach { k ->
                        Box(Modifier.weight(1f).fillMaxHeight().drawBehind {
                            drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()); drawLine(line, Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx())
                        }.clickable { onKey(k) }.testTag("key_$k"), contentAlignment = Alignment.Center) {
                            if (k == "<") Icon(Icons.AutoMirrored.Outlined.Backspace, "Delete", Modifier.size(26.dp)) else Text(k, fontSize = 22.sp)
                        }
                    }
                }
            }
        }
    }
}

/** BEST 5: the contract's market depth in a dialog, read once when opened. */
@Composable
private fun BestFiveDialog(vm: AppVM, symbol: String, q: com.papertrade.app.data.market.MarketQuote?, onDismiss: () -> Unit) {
    var depth by remember { mutableStateOf<MarketDepth?>(null) }; var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(symbol) { vm.depth(symbol).fold({ depth = it }, { error = it.message }) }
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onDismiss) { Text("Close") } }, title = { Text("Market depth") },
        text = { DepthCard(depth, q, error) }, containerColor = Color.White)
}

/** Groww's dotted underline under tappable labels. */
internal fun Modifier.dashedUnderline(color: Color) = drawBehind {
    val y = size.height + 2.dp.toPx()
    drawLine(color, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
}
