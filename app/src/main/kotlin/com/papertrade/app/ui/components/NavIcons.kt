package com.papertrade.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.papertrade.app.ui.theme.Accent

/**
 * Thin line icons for the bottom bar and the Positions toolbar, tinted by Icon. The `…Selected` bottom-bar icons are
 * solid blue with white details and carry their own colours (use tint = Color.Unspecified).
 */
object NavIcons {
    private fun icon(name: String, block: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.line(width: Float = 1.7f, color: Color = Color.Black, body: PathBuilder.() -> Unit) =
        path(stroke = SolidColor(color), strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = body)

    /** A solid shape: filled and stroked in [color], so it has the same outline as the line version. */
    private fun ImageVector.Builder.solid(color: Color = Accent, body: PathBuilder.() -> Unit) =
        path(fill = SolidColor(color), stroke = SolidColor(color), strokeLineWidth = 1.7f, strokeLineJoin = StrokeJoin.Round, pathBuilder = body)

    private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float) {
        moveTo(l + rad, t); lineTo(r - rad, t); arcTo(rad, rad, 0f, false, true, r, t + rad)
        lineTo(r, b - rad); arcTo(rad, rad, 0f, false, true, r - rad, b)
        lineTo(l + rad, b); arcTo(rad, rad, 0f, false, true, l, b - rad)
        lineTo(l, t + rad); arcTo(rad, rad, 0f, false, true, l + rad, t); close()
    }

    private fun PathBuilder.star() {
        moveTo(12f, 7f); lineTo(13.29f, 10.22f); lineTo(16.76f, 10.45f); lineTo(14.09f, 12.68f); lineTo(14.94f, 16.05f)
        lineTo(12f, 14.2f); lineTo(9.06f, 16.05f); lineTo(9.91f, 12.68f); lineTo(7.24f, 10.45f); lineTo(10.71f, 10.22f); close()
    }

    private fun PathBuilder.house() {
        moveTo(4f, 10.2f); lineTo(11.1f, 4.6f); arcTo(1.5f, 1.5f, 0f, false, true, 12.9f, 4.6f); lineTo(20f, 10.2f)
        lineTo(20f, 18.5f); arcTo(1.5f, 1.5f, 0f, false, true, 18.5f, 20f); lineTo(5.5f, 20f); arcTo(1.5f, 1.5f, 0f, false, true, 4f, 18.5f); close()
    }
    private fun PathBuilder.door() { moveTo(12f, 15.5f); lineTo(12f, 17.5f) }

    private fun PathBuilder.folderShape() {
        moveTo(3.5f, 6.5f); arcTo(1.5f, 1.5f, 0f, false, true, 5f, 5f); lineTo(9.3f, 5f); lineTo(11.3f, 7.5f); lineTo(19f, 7.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 20.5f, 9f); lineTo(20.5f, 18.5f); arcTo(1.5f, 1.5f, 0f, false, true, 19f, 20f)
        lineTo(5f, 20f); arcTo(1.5f, 1.5f, 0f, false, true, 3.5f, 18.5f); close()
    }
    private fun PathBuilder.dash() { moveTo(10f, 15f); lineTo(14f, 15f) }

    private fun PathBuilder.rupee() {
        moveTo(7f, 11f); lineTo(11.5f, 11f); moveTo(7f, 12.8f); lineTo(11.5f, 12.8f); moveTo(7.6f, 11f); curveTo(10.6f, 11f, 10.6f, 14.6f, 7.6f, 14.6f); lineTo(10.8f, 17.4f)
    }

    val home = icon("NavHome") { line { house() }; line { door() } }
    val homeSelected = icon("NavHomeSelected") { solid { house() }; line(color = Color.White) { door() } }

    val star = icon("NavStar") { line { roundRect(3.5f, 3.5f, 20.5f, 20.5f, 3f) }; line(1.5f) { star() } }
    val starSelected = icon("NavStarSelected") {
        path(fill = SolidColor(Accent)) { roundRect(3.5f, 3.5f, 20.5f, 20.5f, 3f) }
        path(fill = SolidColor(Color.White), stroke = SolidColor(Color.White), strokeLineWidth = 0.8f, strokeLineJoin = StrokeJoin.Round) { star() }
    }

    val folder = icon("NavFolder") { line { folderShape() }; line { dash() } }
    val folderSelected = icon("NavFolderSelected") { solid { folderShape() }; line(color = Color.White) { dash() } }

    val clipboard = icon("NavClipboard") {
        line {
            moveTo(9f, 5f); lineTo(7.5f, 5f); arcTo(2f, 2f, 0f, false, false, 5.5f, 7f); lineTo(5.5f, 19f); arcTo(2f, 2f, 0f, false, false, 7.5f, 21f)
            lineTo(16.5f, 21f); arcTo(2f, 2f, 0f, false, false, 18.5f, 19f); lineTo(18.5f, 7f); arcTo(2f, 2f, 0f, false, false, 16.5f, 5f); lineTo(15f, 5f)
        }
        line { roundRect(9f, 3f, 15f, 6.5f, 1f) }
    }
    /** A solid board with its clip as a notch across the top, as in the reference's selected ORDERS. */
    val clipboardSelected = icon("NavClipboardSelected") {
        solid { roundRect(5.5f, 4.5f, 18.5f, 21f, 2f) }
        line(1.4f, Color.White) { moveTo(9.5f, 6.8f); lineTo(14.5f, 6.8f) }
    }

    val wallet = icon("NavWallet") {
        line { roundRect(3.5f, 7.5f, 19.5f, 20f, 2f) }
        line { moveTo(6f, 7.5f); lineTo(15f, 4f); lineTo(16.5f, 7.5f) }
        line { roundRect(14.5f, 11.5f, 21f, 16f, 1.5f) }
        line(1.5f) { rupee() }
    }

    // Positions toolbar.

    /** Three upright sliders, each line broken around its knob. */
    val sliders = icon("Sliders") {
        line(1.6f) {
            moveTo(6f, 4f); lineTo(6f, 12.5f); moveTo(6f, 15.5f); lineTo(6f, 20f); moveTo(4.2f, 14f); lineTo(7.8f, 14f)
            moveTo(12f, 4f); lineTo(12f, 6.5f); moveTo(12f, 9.5f); lineTo(12f, 20f); moveTo(10.2f, 8f); lineTo(13.8f, 8f)
            moveTo(18f, 4f); lineTo(18f, 13.5f); moveTo(18f, 16.5f); lineTo(18f, 20f); moveTo(16.2f, 15f); lineTo(19.8f, 15f)
        }
    }

    /** A thin magnifier: open circle, short handle. */
    val search = icon("Search") {
        line(1.6f) { moveTo(18f, 10.5f); arcTo(7f, 7f, 0f, true, true, 4f, 10.5f); arcTo(7f, 7f, 0f, true, true, 18f, 10.5f); close() }
        line(1.6f) { moveTo(16f, 16f); lineTo(20.5f, 20.5f) }
    }

    /**
     * A shield whose top dips inward to a peak in the middle; its right side stops halfway down, leaving room for a ↗
     * arrow at the lower right.
     */
    val secureExit = icon("SecureExit") {
        line(1.6f) {
            moveTo(20.2f, 12.6f); lineTo(20.2f, 6.2f); curveTo(17.2f, 6.2f, 14.4f, 5f, 12f, 3f); curveTo(9.6f, 5f, 6.8f, 6.2f, 3.6f, 6.2f)
            lineTo(3.6f, 10.6f); curveTo(3.6f, 15.6f, 7.2f, 19.6f, 12f, 21.4f)
        }
        line(1.6f) { moveTo(13.6f, 21.2f); lineTo(19.4f, 15.6f); moveTo(15f, 15.6f); lineTo(19.4f, 15.6f); lineTo(19.4f, 20f) }
    }

    /** A rounded square with a ↗ arrow, with a second square showing behind it at the top left. */
    val exitAll = icon("ExitAll") {
        line(1.6f) { moveTo(4.5f, 16f); lineTo(4.5f, 6f); arcTo(2f, 2f, 0f, false, true, 6.5f, 4f); lineTo(16f, 4f) }
        line(1.6f) { roundRect(8f, 7.5f, 20f, 19.5f, 2.2f) }
        line(1.6f) { moveTo(11.5f, 16f); lineTo(16.2f, 11.3f); moveTo(12.6f, 11.3f); lineTo(16.2f, 11.3f); lineTo(16.2f, 14.9f) }
    }

    // Quote sheet and Options-tab pills.

    private val Amber = Color(0xFFF4B400); private val CandleGreen = Color(0xFF00916E); private val CandleRed = Color(0xFFD64F4F)

    private fun PathBuilder.bigStar() {
        moveTo(12f, 3f); lineTo(14.47f, 9.1f); lineTo(21.04f, 9.56f); lineTo(15.99f, 13.8f); lineTo(17.58f, 20.19f)
        lineTo(12f, 16.7f); lineTo(6.42f, 20.19f); lineTo(8.01f, 13.8f); lineTo(2.96f, 9.56f); lineTo(9.53f, 9.1f); close()
    }

    /** A thin amber star with a pale fill; [watchStarOn] is solid. Both carry their own colours (tint = Color.Unspecified). */
    val watchStar = icon("WatchStar") { path(fill = SolidColor(Color(0xFFFFF4D6)), stroke = SolidColor(Amber), strokeLineWidth = 1.5f, strokeLineJoin = StrokeJoin.Round) { bigStar() } }
    val watchStarOn = icon("WatchStarOn") { path(fill = SolidColor(Amber), stroke = SolidColor(Amber), strokeLineWidth = 1.5f, strokeLineJoin = StrokeJoin.Round) { bigStar() } }

    /** Two chain links on the diagonal, thin line, tinted by Icon. */
    val chainLink = icon("ChainLink") {
        line(1.8f) { moveTo(10f, 13f); arcToRelative(5f, 5f, 0f, false, false, 7.54f, 0.54f); lineToRelative(3f, -3f); arcToRelative(5f, 5f, 0f, false, false, -7.07f, -7.07f); lineToRelative(-1.72f, 1.71f) }
        line(1.8f) { moveTo(14f, 11f); arcToRelative(5f, 5f, 0f, false, false, -7.54f, -0.54f); lineToRelative(-3f, 3f); arcToRelative(5f, 5f, 0f, false, false, 7.07f, 7.07f); lineToRelative(1.71f, -1.71f) }
    }

    /** Three candles, green / red / green, each a wick through a solid body. Carries its own colours. */
    val candles = icon("Candles") {
        listOf(Triple(4.5f, CandleGreen, floatArrayOf(4f, 8f, 16f, 21f)), Triple(12f, CandleRed, floatArrayOf(3f, 6f, 13f, 17f)),
            Triple(19.5f, CandleGreen, floatArrayOf(5f, 9f, 15f, 20f))).forEach { (x, c, y) ->
            line(2f, c) { moveTo(x, y[0]); lineTo(x, y[3]) }
            path(fill = SolidColor(c)) { roundRect(x - 2.4f, y[1], x + 2.4f, y[2], 0.6f) }
        }
    }
}
