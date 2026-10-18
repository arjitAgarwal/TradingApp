package com.papertrade.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.papertrade.app.data.market.InstrumentInfo
import com.papertrade.app.data.market.MarketQuote
import com.papertrade.app.ui.AppVM
import com.papertrade.app.ui.Fmt
import com.papertrade.app.ui.components.StatRow
import com.papertrade.core.*
import java.math.BigDecimal

/** SIMULATED order ticket. Nothing here can reach a broker. Quantity is lots for F&O, shares for equity. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderTicket(info: InstrumentInfo, quote: MarketQuote?, vm: AppVM, initialSide: Side, onClose: () -> Unit) {
    var side by remember { mutableStateOf(initialSide) }
    var type by remember { mutableStateOf(OrderType.MARKET) }
    var qty by remember { mutableStateOf("1") }
    var limit by remember { mutableStateOf(quote?.ltp?.toPlainString() ?: "") }
    val q = qty.toIntOrNull() ?: 0
    val lp = limit.toBigDecimalOrNull()
    val ref = if (type == OrderType.LIMIT) lp else (if (side == Side.BUY) quote?.ask else quote?.bid) ?: quote?.ltp
    val notional = ref?.let { it * BigDecimal(q) * BigDecimal(info.lotSize) }
    val margin = when {
        info.segment == Segment.FUTURES && notional != null -> notional * MarginModel().futuresPct.movePointLeft(2)
        info.segment == Segment.OPTION && side == Side.SELL && info.strike != null -> info.strike * BigDecimal(q) * BigDecimal(info.lotSize) * MarginModel().shortOptionPct.movePointLeft(2)
        else -> null
    }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.padding(16.dp).testTag("order_ticket"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SIMULATED ORDER — no real money, no broker", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            Text(info.symbol, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Side.values().forEach { s -> FilterChip(selected = side == s, onClick = { side = s }, label = { Text(s.name) }, modifier = Modifier.testTag("side_${s.name}")) }
                OrderType.values().forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.name) }, modifier = Modifier.testTag("type_${t.name}")) }
            }
            OutlinedTextField(qty, { qty = it.filter(Char::isDigit) }, label = { Text(if (info.segment == Segment.EQUITY) "Quantity (shares)" else "Lots (1 lot = ${info.lotSize})") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth().testTag("qty"))
            if (type == OrderType.LIMIT) OutlinedTextField(limit, { limit = it }, label = { Text("Limit price (tick ${info.tickSize.toPlainString()})") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth().testTag("limit"))
            StatRow("Reference price", ref?.let { Fmt.inr(it) } ?: "unavailable")
            StatRow("Est. value", Fmt.rupee(notional))
            if (margin != null) StatRow("Simulated margin (not broker margin)", Fmt.rupee(margin))
            if (type == OrderType.MARKET && quote == null) Text("No price available — market orders are disabled.", color = MaterialTheme.colorScheme.error)
            Button(onClick = { vm.placeOrder(info, side, type, q, if (type == OrderType.LIMIT) lp else null) { onClose() } },
                enabled = q > 0 && (type == OrderType.LIMIT && lp != null || type == OrderType.MARKET && quote != null), modifier = Modifier.fillMaxWidth().testTag("submit_order")) {
                Text("Place simulated ${side.name}")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
