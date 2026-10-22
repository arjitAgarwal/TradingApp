package com.papertrade.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papertrade.app.data.market.InstrumentInfo
import com.papertrade.app.ui.AppVM
import com.papertrade.app.ui.Fmt
import com.papertrade.app.ui.theme.Gain
import com.papertrade.app.ui.theme.Loss
import com.papertrade.core.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

/**
 * SIMULATED order page (Groww-style, full screen). Nothing here can reach a broker.
 * Quantity is entered in shares; for F&O each -/+ tap is one lot and the engine is sent lots.
 * Depth, Delivery/Intraday and the settings icon are look-only, as agreed with the friend's notes (D1, D2).
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

@Composable
private fun OrderPage(info: InstrumentInfo, vm: AppVM, side: Side, onClose: () -> Unit) {
    val q = vm.quotes.collectAsState().value[info.symbol]
    val valuation by vm.valuation.collectAsState()
    val fno = info.segment != Segment.EQUITY
    val lot = if (fno) info.lotSize else 1
    var type by remember { mutableStateOf(OrderType.MARKET) }
    var qtyText by remember { mutableStateOf(lot.toString()) }
    var limitText by remember { mutableStateOf(q?.ltp?.toPlainString() ?: "") }
    var rejected by remember { mutableStateOf<String?>(null) }
    var placing by remember { mutableStateOf(false) }

    val shares = qtyText.toIntOrNull() ?: 0
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
        shares <= 0 -> "Enter a quantity."
        shares % lot != 0 -> "Quantity must be a multiple of the lot size ($lot)."
        type == OrderType.MARKET && q == null -> "No live price right now. Use a limit price."
        type == OrderType.MARKET && q?.source == QuoteSource.CACHED -> "Market is closed. Market orders are off; use a limit price."
        type == OrderType.LIMIT && (lp == null || lp.signum() <= 0) -> "Enter a limit price."
        else -> null
    }
    val sideColor = if (side == Side.BUY) Gain else Loss

    Scaffold { pad ->
        Box(Modifier.padding(pad).fillMaxSize().testTag("order_ticket")) {
            Column(Modifier.fillMaxSize().imePadding()) {
                // Header: back, contract name with price and change, look-only Depth.
                Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(Fmt.label(info.symbol, info), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text("${Fmt.rupee(q?.ltp)}  (${Fmt.pct(q?.changePct)})", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Depth", fontSize = 14.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.dashedUnderline(MaterialTheme.colorScheme.outline))
                }
                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    // Look-only product selector.
                    Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Delivery", fontWeight = FontWeight.Medium, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                            .border(1.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 8.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Intraday", color = MaterialTheme.colorScheme.outline, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 8.dp))
                        Spacer(Modifier.width(12.dp)); VerticalDivider(Modifier.height(28.dp)); Spacer(Modifier.width(12.dp))
                        Box(Modifier.size(36.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Settings, null, Modifier.size(20.dp))
                        }
                    }
                    // Quantity: shares on screen; -/+ step one lot.
                    Row(Modifier.fillMaxWidth().padding(top = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Qty", fontSize = 17.sp)
                        if (fno) Text("  ${(shares / lot).coerceAtLeast(0)} lot x $lot", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.width(160.dp).border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton({ rejected = null; qtyText = (shares - lot).coerceAtLeast(lot).toString() }, Modifier.testTag("qty_minus")) { Icon(Icons.Default.Remove, "Less") }
                            NumberField(qtyText, { rejected = null; qtyText = it.filter(Char::isDigit).take(7) }, KeyboardType.Number, Modifier.weight(1f).testTag("qty"))
                            IconButton({ rejected = null; qtyText = (shares - shares % lot + lot).toString() }, Modifier.testTag("qty_plus")) { Icon(Icons.Default.Add, "More") }
                        }
                    }
                    // Price: Market by default (D4); tap the label to switch to Limit.
                    Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.clickable { rejected = null; type = if (type == OrderType.MARKET) OrderType.LIMIT else OrderType.MARKET }.testTag("price_type"), verticalAlignment = Alignment.CenterVertically) {
                            Text("Price ", fontSize = 17.sp)
                            Text(if (type == OrderType.MARKET) "Market" else "Limit", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                            Icon(Icons.Default.UnfoldMore, "Change price type", Modifier.size(20.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.width(160.dp).height(48.dp).border(if (type == OrderType.LIMIT) 1.5.dp else 1.dp,
                            if (type == OrderType.LIMIT) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                            .background(if (type == OrderType.MARKET) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterEnd) {
                            if (type == OrderType.LIMIT) NumberField(limitText, { rejected = null; limitText = it.filter { c -> c.isDigit() || c == '.' }.take(10) }, KeyboardType.Decimal, Modifier.fillMaxWidth().testTag("limit"), TextAlign.End)
                            else Text("At market", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                        }
                    }
                    if (type == OrderType.LIMIT && lp != null && q?.ltp?.signum() == 1) {
                        val diff = (lp - q.ltp).multiply(BigDecimal(100)).divide(q.ltp, 2, RoundingMode.HALF_UP)
                        Text("${if (diff.signum() > 0) "+" else ""}${diff.toPlainString()}% from market", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.End).padding(top = 6.dp))
                    }
                    Text("Add stoploss/target", fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.End).padding(top = 16.dp)
                        .dashedUnderline(MaterialTheme.colorScheme.onSurface).clickable { vm.messages.tryEmit("Stoploss/target is coming next") })
                    Spacer(Modifier.weight(1f))
                    problem?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, fontSize = 14.sp,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp).background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(12.dp)).padding(14.dp).testTag("order_problem"))
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                // Footer: virtual balance, approximate requirement, one Buy/Sell button (no "Add money").
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Balance : ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    Text(Fmt.rupee(valuation?.availableCash, 0), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    Text("Approx req : ", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.dashedUnderline(MaterialTheme.colorScheme.outline))
                    Text(Fmt.rupee(required, 0), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Icon(Icons.Default.Refresh, null, Modifier.padding(start = 6.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button({
                    placing = true
                    vm.placeOrder(info, side, type, shares / lot, if (type == OrderType.LIMIT) lp else null) { r -> placing = false; if (r.error == null) onClose() else rejected = r.error }
                }, Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp).height(52.dp).testTag("submit_order"), enabled = problem == null && !placing,
                    shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = sideColor, contentColor = Color.White)) {
                    Text(if (side == Side.BUY) "Buy" else "Sell", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, keyboard: KeyboardType, modifier: Modifier, align: TextAlign = TextAlign.Center) =
    BasicTextField(value, onChange, modifier, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        textStyle = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, textAlign = align, color = MaterialTheme.colorScheme.onSurface))

/** Groww's dotted underline under tappable labels. */
private fun Modifier.dashedUnderline(color: Color) = drawBehind {
    val y = size.height + 2.dp.toPx()
    drawLine(color, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
}
