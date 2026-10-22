package com.papertrade.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papertrade.app.data.market.Candle
import com.papertrade.app.ui.Fmt
import com.papertrade.app.ui.Freshness
import com.papertrade.app.ui.theme.Demo
import com.papertrade.app.ui.theme.Gain
import com.papertrade.app.ui.theme.Loss
import com.papertrade.app.ui.theme.Warn
import java.math.BigDecimal

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String, val offline: Boolean = false, val title: String? = null) : UiState<Nothing>
}

@Composable
fun DemoBanner() = Text(
    "DEMO DATA — synthetic prices, NOT real market data", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth().background(Demo).padding(vertical = 4.dp).testTag("demo_banner"))

@Composable
fun FreshnessChip(f: Freshness, time: String?) {
    val (label, color) = when (f) {
        Freshness.LIVE -> "LIVE" to Gain; Freshness.DELAYED -> "DELAYED" to Warn; Freshness.STALE -> "STALE" to Warn
        Freshness.CACHED -> "PREVIOUS / CACHED" to Warn; Freshness.DEMO -> "DEMO" to Demo; Freshness.NONE -> "NO DATA" to Loss
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        if (time != null) Text("  $time", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun CenterMessage(title: String, body: String? = null, action: (() -> Unit)? = null, actionLabel: String = "Retry") =
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        if (body != null) Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        if (action != null) Button(onClick = action, modifier = Modifier.padding(top = 12.dp)) { Text(actionLabel) }
    }

@Composable fun Loading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

@Composable
fun <T> StateHost(state: UiState<T>, onRetry: (() -> Unit)? = null, content: @Composable (T) -> Unit) = when (state) {
    UiState.Loading -> Loading()
    is UiState.Error -> CenterMessage(state.title ?: if (state.offline) "You're offline" else "Something went wrong", state.message, onRetry)
    is UiState.Success -> content(state.data)
}

@Composable
fun StatRow(label: String, value: String, valueColor: Color = Color.Unspecified) = Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, color = valueColor, fontWeight = FontWeight.Medium)
}

/** Line chart with draggable/tappable price marker. Candles close prices only; no invented points. */
@Composable
fun PriceChart(candles: List<Candle>, modifier: Modifier = Modifier) {
    if (candles.size < 2) { CenterMessage("No chart data"); return }
    var marker by remember(candles) { mutableStateOf<Int?>(null) }
    val closes = candles.map { it.close.toDouble() }
    val min = closes.min(); val max = closes.max(); val span = (max - min).takeIf { it > 0 } ?: 1.0
    val up = closes.last() >= closes.first(); val line = if (up) Gain else Loss
    Column(modifier) {
        val m = marker
        Text(if (m != null) "${Fmt.inr(candles[m].close)}  ·  ${Fmt.time(candles[m].time)}" else "Drag on the chart to inspect a price", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.fillMaxWidth().height(200.dp).pointerInput(candles) {
            detectDragGestures(onDragStart = { o -> marker = ((o.x / size.width) * (candles.size - 1)).toInt().coerceIn(0, candles.size - 1) },
                onDrag = { c, _ -> marker = ((c.position.x / size.width) * (candles.size - 1)).toInt().coerceIn(0, candles.size - 1) },
                onDragEnd = { marker = null }, onDragCancel = { marker = null })
        }.pointerInput(candles) { detectTapGestures(onTap = { o -> marker = ((o.x / size.width) * (candles.size - 1)).toInt().coerceIn(0, candles.size - 1) }) }) {
            fun pt(i: Int) = Offset(size.width * i / (candles.size - 1), (size.height * (1 - (closes[i] - min) / span)).toFloat())
            val path = Path().apply { moveTo(pt(0).x, pt(0).y); for (i in 1 until candles.size) lineTo(pt(i).x, pt(i).y) }
            drawPath(path, line, style = Stroke(width = 3f))
            marker?.let { i -> val p = pt(i); drawLine(Color.Gray, Offset(p.x, 0f), Offset(p.x, size.height), strokeWidth = 1f); drawCircle(line, 8f, p) }
        }
    }
}

/**
 * Watchlist mini chart (Groww style): today's closes ending at the live price, coloured by the day's change,
 * over a dashed line at the previous close. Draws nothing without a live price (e.g. an expired contract).
 */
@Composable
fun Sparkline(closes: List<BigDecimal>, ltp: BigDecimal?, prevClose: BigDecimal?, change: BigDecimal?, modifier: Modifier = Modifier) {
    val pts = (if (ltp != null) closes + ltp else emptyList()).map { it.toDouble() }
    Canvas(modifier) {
        if (pts.size < 2) return@Canvas
        val base = prevClose?.toDouble()
        val min = minOf(pts.min(), base ?: pts.min()); val max = maxOf(pts.max(), base ?: pts.max()); val span = (max - min).takeIf { it > 0 } ?: 1.0
        fun y(v: Double) = (size.height * (1 - (v - min) / span)).toFloat()
        base?.let { drawLine(Color.Gray.copy(alpha = 0.4f), Offset(0f, y(it)), Offset(size.width, y(it)), strokeWidth = 1.5f,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 6f))) }
        val path = Path().apply { pts.forEachIndexed { i, v -> val x = size.width * i / (pts.size - 1); if (i == 0) moveTo(x, y(v)) else lineTo(x, y(v)) } }
        val up = change?.let { it.signum() >= 0 } ?: (pts.last() >= pts.first())
        drawPath(path, if (up) Gain else Loss, style = Stroke(width = 2.5f))
    }
}

/** Empty tab: a large soft icon in a circle, a title and one line of help, centred. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String? = null) =
    Column(Modifier.fillMaxSize().padding(24.dp).testTag("empty_state"), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(120.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), modifier = Modifier.size(56.dp))
        }
        Text(title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 20.dp))
        if (body != null) Text(body, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
