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
    private fun ImageVector.Builder.solid(color: Color = Accent, width: Float = 1.7f, body: PathBuilder.() -> Unit) =
        path(fill = SolidColor(color), stroke = SolidColor(color), strokeLineWidth = width, strokeLineJoin = StrokeJoin.Round, pathBuilder = body)

    /** An icon drawn in dp units with no margin round it, so its ink can be placed exactly. */
    private fun tight(name: String, w: Float, h: Float, block: ImageVector.Builder.() -> Unit) =
        ImageVector.Builder(name, w.dp, h.dp, w, h).apply(block).build()

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

    /** A pentagon house: pointed roof, sides tapering in to a flat base. */
    private fun PathBuilder.house() {
        moveTo(4.2f, 10.4f); lineTo(12f, 4.4f); lineTo(19.8f, 10.4f); lineTo(18f, 19.6f); lineTo(6f, 19.6f); close()
    }
    private fun PathBuilder.door() { moveTo(12f, 13f); lineTo(12f, 16.8f) }

    private fun PathBuilder.folderShape() {
        moveTo(3.5f, 6.8f); arcTo(1.5f, 1.5f, 0f, false, true, 5f, 5.3f); lineTo(9.3f, 5.3f); lineTo(11.3f, 7.7f); lineTo(19f, 7.7f)
        arcTo(1.5f, 1.5f, 0f, false, true, 20.5f, 9.2f); lineTo(20.5f, 17.7f); arcTo(1.5f, 1.5f, 0f, false, true, 19f, 19.2f)
        lineTo(5f, 19.2f); arcTo(1.5f, 1.5f, 0f, false, true, 3.5f, 17.7f); close()
    }
    private fun PathBuilder.dash() { moveTo(10f, 14.5f); lineTo(14f, 14.5f) }

    private fun PathBuilder.rupee() {
        moveTo(7.1f, 11.2f); lineTo(10.4f, 11.2f); moveTo(7.1f, 13f); lineTo(10.4f, 13f); moveTo(7.6f, 11.2f); curveTo(10.1f, 11.2f, 10.1f, 14.7f, 7.6f, 14.7f); lineTo(9.9f, 17.1f)
    }

    /** A five-pointed star outline round ([cx], [cy]): points at radius [r], inner corners at [inner]. */
    private fun PathBuilder.starAt(cx: Float, cy: Float, r: Float, inner: Float) {
        for (i in 0 until 10) { val a = Math.toRadians(-90.0 + 36.0 * i); val d = if (i % 2 == 0) r else inner
            val x = cx + (d * Math.cos(a)).toFloat(); val y = cy + (d * Math.sin(a)).toFloat(); if (i == 0) moveTo(x, y) else lineTo(x, y) }
        close()
    }

    // Bottom-bar line widths come to about 1.4 dp on screen at each icon's own size (HOME 25, WATCHLIST and ORDERS 22, PORTFOLIO 26, ACCOUNT 27 dp).
    val home = icon("NavHome") { line(1.36f) { house() }; line(1.5f) { door() } }
    val homeSelected = icon("NavHomeSelected") { solid(width = 1.36f) { house() }; line(1.5f, Color.White) { door() } }

    val star = icon("NavStar") { line(1.55f) { roundRect(3.5f, 3.5f, 20.5f, 20.5f, 1.2f) }; line(1.6f) { starAt(12f, 12.5f, 4.5f, 2.25f) } }
    val starSelected = icon("NavStarSelected") {
        path(fill = SolidColor(Accent)) { roundRect(3.5f, 3.5f, 20.5f, 20.5f, 2f) }
        path(fill = SolidColor(Color.White), stroke = SolidColor(Color.White), strokeLineWidth = 0.8f, strokeLineJoin = StrokeJoin.Round) { star() }
    }

    val folder = icon("NavFolder") { line(1.4f) { folderShape() }; line(1.4f) { dash() } }
    val folderSelected = icon("NavFolderSelected") { solid { folderShape() }; line(color = Color.White) { dash() } }

    val clipboard = icon("NavClipboard") {
        line(1.55f) {
            moveTo(9f, 5f); lineTo(7.5f, 5f); arcTo(2f, 2f, 0f, false, false, 5.5f, 7f); lineTo(5.5f, 19f); arcTo(2f, 2f, 0f, false, false, 7.5f, 21f)
            lineTo(16.5f, 21f); arcTo(2f, 2f, 0f, false, false, 18.5f, 19f); lineTo(18.5f, 7f); arcTo(2f, 2f, 0f, false, false, 16.5f, 5f); lineTo(15f, 5f)
        }
        line(1.55f) { roundRect(9f, 3f, 15f, 6.5f, 1f) }
    }
    /** A solid board with a hollow clip tab standing on its top edge, as in the reference's selected ORDERS. */
    val clipboardSelected = icon("NavClipboardSelected") {
        path(fill = SolidColor(Accent)) { roundRect(4.85f, 3.6f, 19.15f, 22.3f, 2.3f) }
        path(fill = SolidColor(Accent)) { roundRect(8.2f, 2f, 15.8f, 8f, 1.5f) }
        path(fill = SolidColor(Color.White)) { roundRect(9.4f, 3.4f, 14.6f, 5.05f, 0.8f) }
    }

    val wallet = icon("NavWallet") {
        // The body, open on the right where the clasp crosses its edge; a card slanting out of the top; the clasp pill.
        line(1.26f) {
            moveTo(20f, 12.5f); lineTo(20f, 9.85f); arcTo(1.8f, 1.8f, 0f, false, false, 18.2f, 8.05f); lineTo(5.3f, 8.05f); arcTo(1.8f, 1.8f, 0f, false, false, 3.5f, 9.85f)
            lineTo(3.5f, 18.2f); arcTo(1.8f, 1.8f, 0f, false, false, 5.3f, 20f); lineTo(18.2f, 20f); arcTo(1.8f, 1.8f, 0f, false, false, 20f, 18.2f); lineTo(20f, 15.5f)
        }
        line(1.26f) { moveTo(7.7f, 8.05f); lineTo(14.7f, 3.7f); lineTo(16.9f, 8.05f); moveTo(12.3f, 8.05f); lineTo(15.8f, 5.9f) }
        line(1.26f) { roundRect(16f, 12.5f, 21f, 15.5f, 1.5f) }
        line(1.1f) { rupee() }
    }

    // Portfolio header and summary card.

    /** A thin outline person: round head over open shoulders. */
    val person = icon("Person") {
        line(1.6f) { moveTo(15.6f, 8f); arcTo(3.6f, 3.6f, 0f, true, true, 8.4f, 8f); arcTo(3.6f, 3.6f, 0f, true, true, 15.6f, 8f); close() }
        line(1.6f) { moveTo(5f, 20.5f); lineTo(5f, 19f); arcTo(4.5f, 4.5f, 0f, false, true, 9.5f, 14.5f); lineTo(14.5f, 14.5f); arcTo(4.5f, 4.5f, 0f, false, true, 19f, 19f); lineTo(19f, 20.5f) }
    }

    private fun PathBuilder.eyeShape() {
        moveTo(2f, 12f); curveTo(5.5f, 4f, 18.5f, 4f, 22f, 12f); curveTo(18.5f, 20f, 5.5f, 20f, 2f, 12f); close()
        moveTo(15.4f, 12f); arcTo(3.4f, 3.4f, 0f, true, true, 8.6f, 12f); arcTo(3.4f, 3.4f, 0f, true, true, 15.4f, 12f); close()
    }
    /** A thin outline eye; [eyeOff] has a slash through it. */
    val eye = icon("Eye") { line(1.4f) { eyeShape() } }
    val eyeOff = icon("EyeOff") { line(1.4f) { eyeShape() }; line(1.4f) { moveTo(4.5f, 4f); lineTo(19.5f, 20f) } }

    /** A thin outline padlock with no keyhole. */
    val lock = icon("Lock") {
        line(2f) { roundRect(4.5f, 10.5f, 19.5f, 21.5f, 2.5f) }
        line(2f) { moveTo(7.5f, 10.5f); lineTo(7.5f, 7.5f); arcTo(4.5f, 4.5f, 0f, false, true, 16.5f, 7.5f); lineTo(16.5f, 10.5f) }
    }

    /** Line arrows for gain and loss, 7.5 x 8.75 dp of ink with no margin (size them a little larger where the reference does). */
    val arrowUp = tight("ArrowUp", 7.5f, 8.75f) { line(1.4f) { moveTo(3.75f, 8.05f); lineTo(3.75f, 0.7f); moveTo(0.7f, 3.75f); lineTo(3.75f, 0.7f); lineTo(6.8f, 3.75f) } }
    val arrowDown = tight("ArrowDown", 7.5f, 8.75f) { line(1.4f) { moveTo(3.75f, 0.7f); lineTo(3.75f, 8.05f); moveTo(0.7f, 5f); lineTo(3.75f, 8.05f); lineTo(6.8f, 5f) } }

    // Positions toolbar.

    private fun PathBuilder.rect(l: Float, t: Float, r: Float, b: Float) { moveTo(l, t); lineTo(r, t); lineTo(r, b); lineTo(l, b); close() }

    /**
     * Three upright sliders, square-ended, 16.5 x 13.5 dp of ink with no margin. Each is a line, a gap, then a wide knob
     * with a short stub beyond it; the outer two have the knob near the bottom and the middle one near the top.
     */
    val sliders = tight("Sliders", 16.5f, 13.5f) {
        path(fill = SolidColor(Color.Black)) {
            listOf(2.25f to false, 8.25f to true, 14.25f to false).forEach { (x, flipped) ->
                fun y(v: Float) = if (flipped) 13.5f - v else v
                rect(x - 0.7f, y(0f), x + 0.7f, y(7.55f)); rect(x - 2.25f, y(9f), x + 2.25f, y(11.25f)); rect(x - 0.7f, y(11.25f), x + 0.7f, y(13.5f))
            }
        }
    }

    /** A thin magnifier: a wide open circle and a short, slightly finer handle, 17.9 dp of ink with no margin. */
    val search = tight("Search", 17.9f, 17.9f) {
        line(1.6f) { moveTo(15f, 7.9f); arcTo(7.1f, 7.1f, 0f, true, true, 0.8f, 7.9f); arcTo(7.1f, 7.1f, 0f, true, true, 15f, 7.9f); close() }
        line(1.45f) { moveTo(12.9f, 12.9f); lineTo(17.15f, 17.15f) }
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
