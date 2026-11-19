package com.papertrade.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papertrade.app.ui.AppVM
import com.papertrade.app.ui.Fmt
import com.papertrade.app.ui.theme.Accent
import com.papertrade.core.MarketHours
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

// ACCOUNT, laid out from the reference at 360 dp: full-width white cards with 4 dp corners, 8 dp apart on the grey page.
private val AccInk = Color(0xFF1E2129); private val AccSub = Color(0xFF3D4450)
private val PayLavender = Color(0xFFE8E5F4); private val GaugePurple = Color(0xFF6B3FD3); private val FoldGreen = Color(0xFF5DBB9C)
private val ButtonLine = Color(0xFF3552CC); private val ChatGreen = Color(0xFF25D366)
private val AccCard = RoundedCornerShape(4.dp)

/**
 * The ACCOUNT tab. Its first view is the reference's: "Your Account", the Trading Balance card (available cash, live, and
 * when it last changed), the Pledge Holdings / Pay Later cards, the profile, Refer & Earn and Manage Segments. Only the
 * balance is live; everything else there is static and opens nothing. All the existing settings follow below, and the
 * static GET HELP pill and chat button float over the page.
 */
@Composable
internal fun AccountPage(vm: AppVM, nav: Nav) {
    Box(Modifier.fillMaxSize().background(bandColor())) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("account_page")) {
            Box(Modifier.fillMaxWidth().height(59.9.dp)) {
                Text("Your Account", Modifier.padding(start = 12.dp).paddingFromBaseline(top = 35.5.dp), fontSize = 18.sp, lineHeight = 24.sp,
                    fontWeight = FontWeight.SemiBold, color = ink(AccInk), maxLines = 1, softWrap = false)
            }
            BalanceCard(vm)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().height(156.65.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OfferCard("PLEDGE HOLDINGS", "Pledge stocks or mutual funds you hold to increase trading balance", Mint, "account_pledge", Modifier.weight(1f)) { drawPledge(Mint) }
                OfferCard("PAY LATER (MTF)", "View and analyse your MTF stocks", PayLavender, "account_mtf", Modifier.weight(1f)) { drawGauge() }
            }
            Spacer(Modifier.height(8.dp))
            ProfileCard()
            Spacer(Modifier.height(8.dp))
            TileCard("Refer & Earn ₹600", Mint, "account_refer", chevron = false, glyph = { drawRefer() }) {
                Text("Get ₹200 for each referral", fontSize = 12.sp, lineHeight = 16.sp, color = ink(AccSub), maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.height(8.dp))
            TileCard("Manage Segments", PayLavender, "account_segments", chevron = true, glyph = { drawSegments(PayLavender) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("2 Active", fontSize = 12.sp, lineHeight = 16.sp, color = ink(AccSub), maxLines = 1, softWrap = false)
                    Box(Modifier.padding(horizontal = 8.dp).size(1.dp, 15.dp).background(SegmentRule))
                    Text("2 Inactive", fontSize = 12.sp, lineHeight = 16.sp, color = ink(AccSub), maxLines = 1, softWrap = false)
                }
            }
            Spacer(Modifier.height(8.dp))
            AccountSettings(vm, nav)
            Spacer(Modifier.height(96.dp)) // the last card can scroll clear of the floating buttons
        }
        HelpPill(Modifier.align(Alignment.BottomCenter).padding(bottom = 27.9.dp))
        Canvas(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 33.dp).size(40.dp).testTag("account_chat")) { drawCircle(ChatGreen); drawChat() }
    }
}

private val SegmentRule = Color(0xFFD5D9E0)
private val clockFmt = DateTimeFormatter.ofPattern("hh:mm", Locale.ENGLISH); private val dayFmt = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

/** "Last updated at 08:19 am on 19 November 2026" (IST) for [i]. */
internal fun updatedLine(i: Instant) = i.atZone(MarketHours.IST).let { z -> "Last updated at ${clockFmt.format(z)} ${if (z.hour < 12) "am" else "pm"} on ${dayFmt.format(z)}" }

/** When the balance last changed: the newest cash movement (the ledger is exactly those), but never more than 5 minutes ago. */
internal fun balanceUpdatedAt(ledgerTimes: List<Instant>, now: Instant): Instant = (ledgerTimes + now.minusSeconds(300)).max()

/**
 * Trading Balance: the available cash under a small ₹, a › (static), the "Last updated" pill, WITHDRAW / ADD FUNDS and the
 * pledge strip (all static). 222.6 dp tall; every line sits on the reference's baseline.
 */
@Composable
private fun BalanceCard(vm: AppVM) {
    val v by vm.valuation.collectAsState(); val acc by vm.account.collectAsState(); val now by vm.now.collectAsState()
    val updated = balanceUpdatedAt(acc?.ledger?.map { it.time }.orEmpty(), now)
    Box(Modifier.fillMaxWidth().height(222.6.dp).clip(AccCard).background(MaterialTheme.colorScheme.surface).testTag("account_balance_card")) {
        Text("Trading Balance", Modifier.padding(start = 16.dp).paddingFromBaseline(top = 29.2.dp), fontSize = 12.sp, lineHeight = 16.sp, color = ink(AccSub), maxLines = 1, softWrap = false)
        Text("₹", Modifier.padding(start = 16.dp).paddingFromBaseline(top = 49.7.dp), fontSize = 12.sp, lineHeight = 16.sp, color = ink(AccInk))
        Text(Fmt.inr(v?.availableCash), Modifier.padding(start = 24.3.dp).paddingFromBaseline(top = 56.9.dp).testTag("account_balance"), fontSize = 26.sp, lineHeight = 32.sp,
            color = ink(AccInk), maxLines = 1, softWrap = false)
        BigChevron(Modifier.offset(x = 321.6.dp, y = 26.95.dp))
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 71.4.dp).fillMaxWidth().height(23.4.dp).clip(AccCard).background(bandColor()))
        Icon(HistoryIcon, null, Modifier.offset(x = 23.5.dp, y = 76.7.dp).size(13.dp), tint = ink(AccSub))
        Text(updatedLine(updated), Modifier.padding(start = 39.8.dp).paddingFromBaseline(top = 86.5.dp).testTag("account_updated"), fontSize = 10.sp, lineHeight = 14.sp,
            fontWeight = FontWeight.Medium, color = ink(AccInk), maxLines = 1, softWrap = false)
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 111.15.dp).fillMaxWidth().height(51.07.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).fillMaxHeight().clip(AccCard).border(1.dp, ButtonLine, AccCard).testTag("account_withdraw"), contentAlignment = Alignment.TopCenter) {
                Text("WITHDRAW", Modifier.paddingFromBaseline(top = 31.dp), fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = Accent, maxLines = 1, softWrap = false)
            }
            Box(Modifier.weight(1f).fillMaxHeight().clip(AccCard).background(Accent).testTag("account_add_funds"), contentAlignment = Alignment.TopCenter) {
                Text("ADD FUNDS", Modifier.paddingFromBaseline(top = 31.dp), fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = Color.White, maxLines = 1, softWrap = false)
            }
        }
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 178.2.dp).fillMaxWidth().height(28.6.dp).clip(AccCard).background(bandColor()).testTag("account_pledge_strip")) {
            Icon(TrendingIcon, null, Modifier.align(Alignment.CenterStart).padding(start = 7.8.dp).size(16.dp), tint = ink(AccInk))
            Text("Get ₹42,134 for MTF, F&O & Intraday", Modifier.padding(start = 31.6.dp).paddingFromBaseline(top = 19.1.dp), fontSize = 12.sp, lineHeight = 16.sp,
                color = ink(AccSub), maxLines = 1, softWrap = false)
            Text("PLEDGE NOW", Modifier.align(Alignment.TopEnd).padding(end = 7.75.dp).paddingFromBaseline(top = 19.1.dp), fontSize = 12.sp, lineHeight = 16.sp,
                fontWeight = FontWeight.Medium, color = Accent, maxLines = 1, softWrap = false)
        }
    }
}

/** Pledge Holdings / Pay Later: a 40 dp disc 8 dp down, the blue title with a ›, and a 12 sp description in 16 dp lines. Static. */
@Composable
private fun OfferCard(title: String, text: String, disc: Color, tag: String, modifier: Modifier, glyph: DrawScope.() -> Unit) {
    Box(modifier.fillMaxHeight().clip(AccCard).background(MaterialTheme.colorScheme.surface).testTag(tag)) {
        Canvas(Modifier.padding(start = 16.dp, top = 8.dp).size(40.dp)) { drawCircle(disc); glyph() }
        Row(Modifier.padding(start = 16.dp).paddingFromBaseline(top = 76.7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, color = Accent, maxLines = 1, softWrap = false)
            SmallChevron(Modifier.padding(start = 7.7.dp))
        }
        Text(text, Modifier.padding(start = 16.dp, end = 16.dp).paddingFromBaseline(top = 105.2.dp), fontSize = 12.sp, lineHeight = 16.sp, color = ink(AccSub))
    }
}

/** The profile row: the avatar, the reference's name and client ID (static text), and a ›. */
@Composable
private fun ProfileCard() {
    Box(Modifier.fillMaxWidth().height(77.95.dp).clip(AccCard).background(MaterialTheme.colorScheme.surface).testTag("account_profile")) {
        Canvas(Modifier.align(Alignment.CenterStart).padding(start = 16.dp).size(40.dp)) { drawCircle(HomeDisc); drawAvatar() }
        Text("Jayendra Choudhary", Modifier.padding(start = 72.dp).paddingFromBaseline(top = 33.05.dp), fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
            color = ink(AccInk), maxLines = 1, softWrap = false)
        Text("Client ID: AABY899707", Modifier.padding(start = 72.dp).paddingFromBaseline(top = 58.6.dp), fontSize = 12.sp, lineHeight = 16.sp, color = ink(AccSub), maxLines = 1, softWrap = false)
        BigChevron(Modifier.offset(x = 321.6.dp, y = 29.05.dp))
    }
}

/** A 64 dp row card: a 40 dp disc, a 16 sp title over [sub], and optionally a › centred on the right. Static. */
@Composable
private fun TileCard(title: String, disc: Color, tag: String, chevron: Boolean, glyph: DrawScope.() -> Unit, sub: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(64.dp).clip(AccCard).background(MaterialTheme.colorScheme.surface).testTag(tag)) {
        Canvas(Modifier.align(Alignment.CenterStart).padding(start = 16.dp).size(40.dp)) { drawCircle(disc); glyph() }
        Text(title, Modifier.padding(start = 68.dp).paddingFromBaseline(top = 29.05.dp), fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
            color = ink(AccInk), maxLines = 1, softWrap = false)
        Box(Modifier.padding(start = 68.dp).paddingFromBaseline(top = 50.65.dp)) { sub() }
        if (chevron) BigChevron(Modifier.align(Alignment.CenterStart).offset(x = 321.6.dp))
    }
}

/** The floating "? GET HELP" pill, 116 x 40 dp. Static. */
@Composable
private fun HelpPill(modifier: Modifier) {
    Box(modifier.size(116.dp, 40.dp).clip(CircleShape).background(Accent).testTag("account_get_help")) {
        Icon(HelpIcon, null, Modifier.align(Alignment.CenterStart).padding(start = 15.67.dp).size(16.dp), tint = Color.White)
        Text("GET HELP", Modifier.padding(start = 40.2.dp).paddingFromBaseline(top = 25.dp), fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
            color = Color.White, maxLines = 1, softWrap = false)
    }
}

/** The blue › of the balance, profile and Manage Segments rows: 8.5 x 14.45 dp of ink, a 2.6 dp line. */
@Composable
private fun BigChevron(modifier: Modifier) = Canvas(modifier.size(8.5.dp, 14.45.dp)) { chevron(2.6.dp.toPx()) }

/** The › after the blue card titles: 6.4 x 11.2 dp of ink, a 1.9 dp line. */
@Composable
private fun SmallChevron(modifier: Modifier) = Canvas(modifier.size(6.4.dp, 11.2.dp)) { chevron(1.9.dp.toPx()) }

private fun DrawScope.chevron(sw: Float) {
    val i = sw / 2
    drawPath(Path().apply { moveTo(i, i); lineTo(size.width - i, size.height / 2); lineTo(i, size.height - i) }, Accent, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** A Material Symbols outline (a 960-unit box whose y runs from -960 to 0), filled, for Icon to tint. */
private fun symbol(name: String, d: String) = ImageVector.Builder(name, 24.dp, 24.dp, 960f, 960f).apply {
    addGroup(translationY = 960f); addPath(addPathNodes(d), fill = SolidColor(Color.Black)); clearGroup()
}.build()

/** A clock inside a circular arrow that turns back at the top left. */
private val HistoryIcon = symbol("History", "M480-120q-138 0-240.5-91.5T122-440h82q14 104 92.5 172T480-200q117 0 198.5-81.5T760-480q0-117-81.5-198.5T480-760q-69 0-129 32t-101 88h110v80H120v-240h80v94q51-64 124.5-99T480-840q75 0 140.5 28.5t114 77q48.5 48.5 77 114T840-480q0 75-28.5 140.5t-77 114q-48.5 48.5-114 77T480-120Zm112-192L440-464v-216h80v184l128 128-56 56Z")
/** A zigzag rising to an arrow at the top right. */
private val TrendingIcon = symbol("TrendingUp", "m136-240-56-56 296-298 160 160 208-206H640v-80h240v240h-80v-104L536-320 376-480 136-240Z")
/** A question mark in a ring. */
private val HelpIcon = symbol("Help", "M478-240q21 0 35.5-14.5T528-290q0-21-14.5-35.5T478-340q-21 0-35.5 14.5T428-290q0 21 14.5 35.5T478-240Zm-36-154h74q0-33 7.5-52t42.5-52q26-26 41-49.5t15-56.5q0-56-41-86t-97-30q-57 0-92.5 30T342-618l66 26q5-18 22.5-39t53.5-21q32 0 48 17.5t16 38.5q0 20-12 37.5T506-526q-44 39-54 59t-10 73Zm38 314q-83 0-156-31.5T197-197q-54-54-85.5-127T80-480q0-83 31.5-156T197-763q54-54 127-85.5T480-880q83 0 156 31.5T763-763q54 54 85.5 127T880-480q0 83-31.5 156T763-197q-54 54-127 85.5T480-80Zm0-80q134 0 227-93t93-227q0-134-93-227t-227-93q-134 0-227 93t-93 227q0 134 93 227t227 93Zm0-320Z")

// The drawings, in the 40 dp disc's own dp (u = 1 dp).

/** Pledge: a page with a green dog-ear, ← over →, and a green ₹ coin over its bottom right corner, ringed in [disc]. */
private fun DrawScope.drawPledge(disc: Color) {
    val u = size.width / 40f; val line = Stroke(0.95f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawPath(Path().apply { moveTo(23.3f * u, 8.65f * u); lineTo(12.4f * u, 8.65f * u); quadraticTo(10.9f * u, 8.65f * u, 10.9f * u, 10.15f * u); lineTo(10.9f * u, 27.8f * u)
        quadraticTo(10.9f * u, 29.3f * u, 12.4f * u, 29.3f * u); lineTo(26.85f * u, 29.3f * u); quadraticTo(28.35f * u, 29.3f * u, 28.35f * u, 27.8f * u); lineTo(28.35f * u, 13.05f * u); close() }, DrawInk, style = line)
    drawPath(Path().apply { moveTo(23.3f * u, 8.65f * u); lineTo(28.35f * u, 13.05f * u); lineTo(23.3f * u, 13.05f * u); close() }, FoldGreen)
    drawPath(Path().apply { moveTo(20.7f * u, 16.75f * u); lineTo(14.25f * u, 16.75f * u); moveTo(16.4f * u, 14.6f * u); lineTo(14.25f * u, 16.75f * u); lineTo(16.4f * u, 18.9f * u)
        moveTo(17.35f * u, 21f * u); lineTo(24.05f * u, 21f * u); moveTo(21.9f * u, 18.85f * u); lineTo(24.05f * u, 21f * u); lineTo(21.9f * u, 23.15f * u) }, DrawInk, style = line)
    val c = Offset(27f * u, 26.8f * u); drawCircle(disc, 6.1f * u, c); drawCircle(CoinGreen, 4.8f * u, c)
    rupee(c, 0.86f * u)
}

/** A white ₹ centred on [c], drawn at [k] px per unit of the Refer coin's ₹. */
private fun DrawScope.rupee(c: Offset, k: Float) {
    val x = c.x; val y = c.y
    drawPath(Path().apply { moveTo(x - 2.1f * k, y - 2.5f * k); lineTo(x + 2.1f * k, y - 2.5f * k); moveTo(x - 2.1f * k, y - 1.1f * k); lineTo(x + 2.1f * k, y - 1.1f * k)
        moveTo(x - 1.6f * k, y - 2.5f * k); cubicTo(x + 1.6f * k, y - 2.5f * k, x + 1.6f * k, y + 0.6f * k, x - 1.6f * k, y + 0.6f * k); lineTo(x + 1.3f * k, y + 3.1f * k) },
        Color.White, style = Stroke(0.85f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Pay Later: a ring gauge with its top right quarter filled purple between the rings, and % in the middle. */
private fun DrawScope.drawGauge() {
    val u = size.width / 40f; val c = Offset(20.2f * u, 20.2f * u); val sw = 1.05f * u
    fun ring(r: Float) = Rect(c.x - r * u, c.y - r * u, c.x + r * u, c.y + r * u)
    // Angles run clockwise from 3 o'clock; the outer ring stops short of the purple band at both ends.
    drawArc(DrawInk, 2f, 286f, false, ring(11.15f).topLeft, ring(11.15f).size, style = Stroke(sw, cap = StrokeCap.Round))
    drawArc(GaugePurple, -67.6f, 65.6f, false, ring(9.7f).topLeft, ring(9.7f).size, style = Stroke(4.2f * u))
    drawCircle(DrawInk, 6.95f * u, c, style = Stroke(sw))
    val thin = Stroke(0.85f * u, cap = StrokeCap.Round)
    drawCircle(DrawInk, 1.05f * u, c + Offset(-1.75f * u, -1.85f * u), style = thin); drawCircle(DrawInk, 1.05f * u, c + Offset(1.75f * u, 1.85f * u), style = thin)
    drawLine(DrawInk, c + Offset(2.3f * u, -2.9f * u), c + Offset(-2.3f * u, 2.9f * u), 0.85f * u, StrokeCap.Round)
}

/** Profile: a ring for the head over solid blue shoulders, wider at the bottom with a rounded base. */
private fun DrawScope.drawAvatar() {
    val u = size.width / 40f
    drawCircle(DrawInk, 6.3f * u, Offset(20f * u, 13.4f * u), style = Stroke(1.35f * u))
    drawPath(Path().apply { moveTo(9.05f * u, 24.1f * u); lineTo(30.95f * u, 24.1f * u); lineTo(33.35f * u, 31.2f * u); quadraticTo(20f * u, 41.6f * u, 6.65f * u, 31.2f * u); close() }, Accent)
}

/** Manage Segments: a phone with a speaker slot and a bar, and a blue check badge over its bottom right corner, ringed in [disc]. */
private fun DrawScope.drawSegments(disc: Color) {
    val u = size.width / 40f; val line = Stroke(1.25f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawPath(Path().apply { moveTo(27.5f * u, 17.6f * u); lineTo(27.5f * u, 10.4f * u); quadraticTo(27.5f * u, 8.1f * u, 25.2f * u, 8.1f * u); lineTo(15f * u, 8.1f * u)
        quadraticTo(12.7f * u, 8.1f * u, 12.7f * u, 10.4f * u); lineTo(12.7f * u, 28.8f * u); quadraticTo(12.7f * u, 31.1f * u, 15f * u, 31.1f * u); lineTo(18.8f * u, 31.1f * u) }, DrawInk, style = line)
    drawLine(DrawInk, Offset(18.4f * u, 10.3f * u), Offset(21.6f * u, 10.3f * u), 1.2f * u, StrokeCap.Round)
    drawRoundRect(DrawInk, Offset(15.3f * u, 12.8f * u), Size(9.2f * u, 3.2f * u), CornerRadius(0.8f * u), style = Stroke(1.2f * u))
    val c = Offset(25.35f * u, 26.4f * u); drawCircle(disc, 6.5f * u, c); drawCircle(Accent, 5.5f * u, c)
    drawPath(Path().apply { moveTo(c.x - 2.4f * u, c.y + 0.1f * u); lineTo(c.x - 0.7f * u, c.y + 1.8f * u); lineTo(c.x + 2.5f * u, c.y - 1.5f * u) }, Color.White,
        style = Stroke(1.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** The chat button's glyph: a white speech-bubble ring with its tail at the bottom left, and a handset inside. */
private fun DrawScope.drawChat() {
    val u = size.width / 40f; val c = Offset(20f * u, 20f * u); val r = 9.85f * u
    fun at(deg: Float) = Math.toRadians(deg.toDouble()).let { a -> Offset(c.x + r * Math.cos(a).toFloat(), c.y + r * Math.sin(a).toFloat()) }
    drawPath(Path().apply { arcTo(Rect(c, r), 150f, 315f, true); lineTo(9.7f * u, 30.65f * u); lineTo(at(150f).x, at(150f).y) }, Color.White,
        style = Stroke(2.1f * u, join = StrokeJoin.Round))
    drawPath(Path().apply { moveTo(c.x - 3.4f * u, c.y - 3.7f * u); quadraticTo(c.x - 3.2f * u, c.y + 3.2f * u, c.x + 3.7f * u, c.y + 3.4f * u) }, Color.White,
        style = Stroke(2.2f * u, cap = StrokeCap.Round))
    drawCircle(Color.White, 1.45f * u, c + Offset(-3.6f * u, -3.6f * u)); drawCircle(Color.White, 1.45f * u, c + Offset(3.6f * u, 3.5f * u))
}
