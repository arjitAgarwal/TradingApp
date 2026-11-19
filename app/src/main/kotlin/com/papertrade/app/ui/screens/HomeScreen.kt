package com.papertrade.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papertrade.app.data.market.MarketQuote
import com.papertrade.app.data.market.Underlying
import com.papertrade.app.ui.AppVM
import com.papertrade.app.ui.Fmt
import com.papertrade.app.ui.components.NavIcons
import com.papertrade.app.ui.theme.Accent

// HOME, laid out from the reference at 360 dp: grey bands between white sections, hairline cards, near-black titles.
private val HomeGrey = Color(0xFFF5F6FA); private val HomeLine = Color(0xFFE4E6EA); internal val HomeDisc = Color(0xFFF3F6FA)
private val HomeTitle = Color(0xFF3C3F47); private val HomeText = Color(0xFF282B33); private val HomeLabel = Color(0xFF42454E); private val ShortcutInk = Color(0xFF1E2129)
private val HomeTabInk = Color(0xFF545D6B); private val HomeHint = Color(0xFFAEB8C3); private val GlyphInk = Color(0xFF22262F)
private val Lavender = Color(0xFFE6EBFA); internal val Mint = Color(0xFFDDF3ED); private val OverviewFill = Color(0xFFF2F5FB)
private val ProfileDot = Color(0xFFDC4A52)
private val CardShape = RoundedCornerShape(6.dp)

@Composable internal fun bandColor() = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceVariant else HomeGrey
@Composable private fun lineColor() = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.outlineVariant else HomeLine
@Composable internal fun ink(c: Color) = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.onSurface else c

/** Large caps that fill "Stocks You're Interested In" after the watchlists' own stocks. */
private val PopularStocks = listOf("NSE:RELIANCE-EQ", "NSE:HDFCBANK-EQ", "NSE:TCS-EQ", "NSE:INFY-EQ", "NSE:SBIN-EQ")

/** The categories under the HOME header, each with its label's left edge (dp) as placed in the reference. */
internal enum class HomeTab(val label: String, val x: Float) { STOCKS("Stocks", 14.5f), FNO("F&O", 94.8f), MF("Mutual Funds", 151.85f), COMMODITY("Commodity", 266.2f) }

/**
 * HOME's header: a round Products button (a menu of the index option chains and futures), the search box, and a round
 * Profile button that switches to the ACCOUNT tab. Each round button is a 32 dp disc over a 10 sp label; the box is 40 dp tall.
 */
@Composable
internal fun HomeHeader(nav: Nav, onSearch: () -> Unit, onProfile: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().height(65.dp).background(MaterialTheme.colorScheme.surface)) {
        Box {
            HeaderButton("Products", "header_products", { menu = true }) { drawProducts() }
            DropdownMenu(menu, { menu = false }) {
                Underlying.values().forEach { u -> DropdownMenuItem({ Text("${u.displayName} Option Chain") }, { menu = false; nav.chain(u) }, Modifier.testTag("products_chain_${u.name}")) }
                Underlying.values().forEach { u -> DropdownMenuItem({ Text("${u.displayName} Futures") }, { menu = false; nav.futures(u) }, Modifier.testTag("products_futures_${u.name}")) }
            }
        }
        Box(Modifier.align(Alignment.TopEnd)) { HeaderButton("Profile", "header_profile", onProfile) { drawProfile() } }
        // 56 dp in from each side (248 dp wide at 360 dp); the magnifier's ink centred 18 dp in, the hint 36 dp in.
        Row(Modifier.padding(start = 56.dp, end = 56.dp, top = 11.5.dp).fillMaxWidth().height(40.dp).clip(RoundedCornerShape(9.dp))
            .border(1.dp, lineColor(), RoundedCornerShape(9.dp)).clickable(onClick = onSearch).testTag("header_search"), verticalAlignment = Alignment.CenterVertically) {
            Icon(NavIcons.search, null, Modifier.padding(start = 10.75.dp).size(14.5.dp), tint = HomeHint)
            Text("Search for Reliance, Tata", fontSize = 14.sp, lineHeight = 18.sp, color = HomeHint, maxLines = 1, softWrap = false,
                modifier = Modifier.padding(start = 10.85.dp).offset(y = 0.9.dp))
        }
    }
}

/** A 56 dp column: the disc centred 22.75 dp down and the label's baseline 53.8 dp down. */
@Composable
private fun HeaderButton(label: String, tag: String, onClick: () -> Unit, glyph: DrawScope.() -> Unit) {
    Column(Modifier.width(56.4.dp).height(65.dp).clickable(onClick = onClick).testTag(tag), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.padding(top = 6.75.dp).size(32.dp).semantics { contentDescription = label }) { drawCircle(HomeDisc); glyph() }
        Text(label, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Medium, color = ink(HomeLabel), maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 5.65.dp))
    }
}

/** Products: three outlined squares and a blue dot in a 2 x 2 grid, 17.6 dp across, centred on the disc. */
private fun DrawScope.drawProducts() {
    val u = size.width / 32f; val o = Offset(7.2f * u, 7.2f * u); val cell = 7.6f * u; val gap = 2.4f * u; val sw = 1.1f * u
    listOf(0 to 0, 1 to 0, 0 to 1).forEach { (cx, cy) ->
        val tl = o + Offset(cx * (cell + gap) + sw / 2, cy * (cell + gap) + sw / 2)
        drawRoundRect(GlyphInk, tl, Size(cell - sw, cell - sw), CornerRadius(0.9f * u), style = Stroke(sw))
    }
    drawCircle(Accent, 3.3f * u, o + Offset(cell + gap + cell / 2, cell + gap + cell / 2))
}

/** Profile: a ring for the head over solid blue shoulders that the disc cuts off, and a red dot on the rim at top right. */
private fun DrawScope.drawProfile() {
    val u = size.width / 32f; val c = Offset(16f * u, 16f * u)
    drawCircle(GlyphInk, 4.5f * u, c + Offset(0.3f * u, -1.9f * u), style = Stroke(1.3f * u))
    clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset.Zero, size)) }) {
        drawRoundRect(Accent, c + Offset(-10f * u, 6.4f * u), Size(20f * u, 14f * u), CornerRadius(6f * u))
    }
    drawCircle(ProfileDot, 2.9f * u, c + Offset(10.4f * u, -11.7f * u))
}

/**
 * Stocks / F&O / Mutual Funds / Commodity: 14 sp Medium, the selected one blue over a 3 dp bar 6.5 dp wider than its label
 * each side. A red "₹10" pill sits over "Funds", its top just above the row.
 */
@Composable
internal fun HomeTabs(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    Box(Modifier.fillMaxWidth().height(49.dp).background(MaterialTheme.colorScheme.surface)) {
        HomeTab.values().forEach { t -> val on = t == selected
            Column(Modifier.offset(x = (t.x - 6.5f).dp).fillMaxHeight().width(IntrinsicSize.Max).clickable { onSelect(t) }.testTag("home_tab_${t.name}")) {
                Text(t.label, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = if (on) Accent else ink(HomeTabInk), maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(start = 6.5.dp, end = 6.5.dp, top = 14.4.dp))
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth().height(3.dp).background(if (on) Accent else Color.Transparent, RoundedCornerShape(topStart = 1.5.dp, topEnd = 1.5.dp)))
                Spacer(Modifier.height(1.dp))
            }
        }
        Box(Modifier.offset(x = (HomeTab.MF.x + 70.55f).dp, y = (-0.75).dp).size(27.5.dp, 17.5.dp).background(AssetLoss, CircleShape).testTag("home_mf_badge"), contentAlignment = Alignment.Center) {
            Text("₹10", fontSize = 9.5.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, softWrap = false, modifier = Modifier.offset(y = 0.3.dp))
        }
        HorizontalDivider(Modifier.align(Alignment.BottomCenter), color = lineColor())
    }
}

/** HOME below the index strip: the Stocks page, the Options strikes under F&O, and a note for the kinds with no prices yet. */
@Composable
internal fun HomePage(vm: AppVM, nav: Nav, tab: HomeTab, onViewAll: () -> Unit) = when (tab) {
    HomeTab.STOCKS -> StocksHome(vm, nav, onViewAll)
    HomeTab.FNO -> OptionsTab(vm, nav)
    HomeTab.MF, HomeTab.COMMODITY -> Box(Modifier.fillMaxSize().padding(24.dp).testTag("home_soon"), contentAlignment = Alignment.TopCenter) {
        Text(if (tab == HomeTab.MF) "Mutual fund prices are coming soon." else "Commodity prices are coming soon.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The Stocks page as in the reference: the analysts card on a grey band and the Rewards / Refer cards (all three static),
 * "Stocks You're Interested In" (the watchlists' stocks, then a few large caps; tap one for its quote sheet), and
 * "Pre-Market Insights" (a static View All) over the two indices. Sections sit 8 dp apart.
 */
@Composable
private fun StocksHome(vm: AppVM, nav: Nav, onViewAll: () -> Unit) {
    val lists by vm.watchlists.collectAsState(); val quotes by vm.quotes.collectAsState()
    val stocks = remember(lists) { (lists.flatMap { w -> w.items.map { it.symbol } }.filter { it.endsWith("-EQ") } + PopularStocks).distinct().take(10) }
    DisposableEffect(stocks) { vm.subscribe("home", stocks.toSet()); onDispose { vm.unsubscribe("home") } }
    var action by remember { mutableStateOf<String?>(null) }
    val surface = MaterialTheme.colorScheme.surface
    Column(Modifier.fillMaxSize().background(bandColor()).verticalScroll(rememberScrollState()).testTag("home_stocks")) {
        Box(Modifier.fillMaxWidth().padding(8.dp)) { AnalystsCard() }
        Row(Modifier.fillMaxWidth().background(surface).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ShortcutCard("Rewards and\nOffers", Lavender, "home_rewards", Modifier.weight(1f)) { drawGift() }
            ShortcutCard("Refer and Earn\n₹2500", Mint, "home_refer", Modifier.weight(1f)) { drawRefer() }
        }
        Spacer(Modifier.height(8.dp))
        HomeSection("Stocks You’re Interested In", onViewAll) {
            LazyRow(Modifier.testTag("home_stock_row"), contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(stocks, key = { it }) { s -> StockCard(s, quotes[s]) { action = s } }
            }
        }
        Spacer(Modifier.height(8.dp))
        HomeSection("Pre-Market Insights", onViewAll = null, showViewAll = true) { OverviewCard(vm, nav) }
    }
    action?.let { sym -> val holder = lists.firstOrNull { w -> w.items.any { it.symbol == sym } }
        QuickActionSheet(vm, nav, sym, quotes[sym], inList = holder != null, onClose = { action = null }, onOpen = { action = null; nav.stock(sym) },
            onTrade = { side -> action = null; nav.order(sym, side) },
            onStar = { if (holder != null) vm.removeFromWatchlist(holder.id, sym) else lists.firstOrNull()?.let { w -> vm.addToWatchlist(w.id, sym, vm.info(sym)?.name ?: sym) } })
    }
}

/** The white card on the top band, static: a person giving a thumbs-up, a 14 sp title and a blue "Explore ProInsight ›". 64 dp tall. */
@Composable
private fun AnalystsCard() {
    Row(Modifier.fillMaxWidth().height(64.dp).clip(CardShape).border(1.dp, lineColor(), CardShape).background(MaterialTheme.colorScheme.surface).testTag("home_analysts_card")) {
        Canvas(Modifier.offset(y = 0.8.dp).size(64.dp)) { drawAdvisor() }
        Column(Modifier.padding(top = 13.7.dp)) {
            Text("SEBI-verified research analysts for you", fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = ink(HomeText), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 1.4.dp).height(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Explore ProInsight", fontSize = 12.sp, lineHeight = 16.sp, color = Accent, maxLines = 1, softWrap = false)
                Chevron(Modifier.padding(start = 3.3.dp))
            }
        }
    }
}

/** A thin "›", 4.2 x 7.7 dp of ink. */
@Composable
private fun Chevron(modifier: Modifier = Modifier) = Canvas(modifier.size(4.2.dp, 7.7.dp)) {
    val sw = 1.25.dp.toPx(); val i = sw / 2
    drawPath(Path().apply { moveTo(i, i); lineTo(size.width - i, size.height / 2); lineTo(i, size.height - i) }, Accent, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** One of the two static cards under the band: a 40 dp tinted disc with its drawing, then two 12 sp lines. 56 dp tall. */
@Composable
private fun ShortcutCard(text: String, disc: Color, tag: String, modifier: Modifier, glyph: DrawScope.() -> Unit) {
    Row(modifier.height(56.dp).clip(CardShape).border(1.dp, lineColor(), CardShape).background(MaterialTheme.colorScheme.surface).padding(start = 12.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(40.dp)) { drawCircle(disc); glyph() }
        Text(text, fontSize = 12.sp, lineHeight = 16.sp, color = ink(ShortcutInk), modifier = Modifier.padding(start = 8.dp).offset(y = 0.85.dp))
    }
}

/**
 * A white section: the 16 sp heading's baseline 32.5 dp down with "View All ›" level with it (static when [onViewAll] is
 * null), [content] 53 dp down, then 16 dp.
 */
@Composable
private fun HomeSection(title: String, onViewAll: (() -> Unit)?, showViewAll: Boolean = onViewAll != null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 15.dp).height(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, color = ink(HomeTitle), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (showViewAll) {
                Row((onViewAll?.let { Modifier.clickable(onClick = it) } ?: Modifier).padding(start = 8.dp, end = 19.9.dp).testTag(if (onViewAll != null) "home_view_all" else "home_view_all_static"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("View All", fontSize = 14.sp, lineHeight = 20.sp, color = Accent, maxLines = 1, softWrap = false)
                    Chevron(Modifier.padding(start = 4.3.dp).offset(y = (-0.65).dp))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        content()
    }
}

/** A 140 x 144 dp stock card: a lettered disc for the logo, the symbol, the price in the day's colour with ▲/▼, and the change. */
@Composable
private fun StockCard(symbol: String, q: MarketQuote?, onClick: () -> Unit) {
    val name = symbol.substringAfter(':').removeSuffix("-EQ"); val c = pnlInk(q?.change, ink(HomeText))
    Column(Modifier.size(140.dp, 144.dp).clip(CardShape).border(1.dp, lineColor(), CardShape).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(12.dp).testTag("home_stock_$symbol")) {
        Box(Modifier.size(40.dp).background(LogoColors[Math.floorMod(name.hashCode(), LogoColors.size)], CircleShape), contentAlignment = Alignment.Center) {
            Text(name.take(1), fontSize = 18.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = Color.White)
        }
        Text(name, fontSize = 16.sp, lineHeight = 20.sp, color = ink(HomeText), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 9.9.dp))
        Row(Modifier.padding(top = 9.1.dp).height(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(Fmt.rupee(q?.ltp), fontSize = 16.sp, lineHeight = 20.sp, color = c, maxLines = 1, softWrap = false)
            // A solid triangle 2.4 dp after the price, its middle 5.9 dp above the baseline.
            q?.change?.takeIf { it.signum() != 0 }?.let { ch ->
                Canvas(Modifier.padding(start = 2.4.dp).offset(y = (-0.45).dp).size(10.dp, 6.dp)) {
                    val (base, tip) = if (ch.signum() > 0) size.height to 0f else 0f to size.height
                    drawPath(Path().apply { moveTo(0f, base); lineTo(size.width, base); lineTo(size.width / 2, tip); close() }, c)
                }
            }
        }
        Text(q?.change?.let { d -> "${if (d.signum() < 0) "-" else "+"}₹${Fmt.inr(d.abs())} (${Fmt.pct(q.changePct)})" } ?: "—", fontSize = 12.sp, lineHeight = 16.sp,
            color = subInk(), maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 5.3.dp))
    }
}

/** Deep logo colours, picked by the symbol so a stock keeps its colour. */
private val LogoColors = listOf(Color(0xFF1B2A8C), Color(0xFF2E49C9), Color(0xFF00796B), Color(0xFF6A3FB5), Color(0xFFB3423D), Color(0xFF2F7D4F), Color(0xFFC46A12), Color(0xFF455A70))

/** NIFTY 50 and SENSEX side by side on a pale card: level, day's %, then "Option chain · Futures" links. */
@Composable
private fun OverviewCard(vm: AppVM, nav: Nav) {
    val quotes by vm.quotes.collectAsState(); val fill = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.surfaceVariant else OverviewFill
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(IntrinsicSize.Min).clip(CardShape).border(1.dp, lineColor(), CardShape).background(fill)) {
        Underlying.values().forEachIndexed { i, u -> val q = quotes[u.indexSymbol]
            if (i > 0) VerticalDivider(Modifier.padding(vertical = 12.dp), color = lineColor())
            Column(Modifier.weight(1f).clickable { nav.index(u) }.padding(12.dp).testTag("card_${u.name}")) {
                Text(u.displayName, fontSize = 12.sp, lineHeight = 16.sp, color = subInk())
                Text(Fmt.inr(q?.ltp), fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = ink(HomeText), modifier = Modifier.padding(top = 4.dp))
                Text(Fmt.pct(q?.changePct), fontSize = 12.sp, lineHeight = 16.sp, color = pnlInk(q?.changePct, subInk()), modifier = Modifier.padding(top = 2.dp))
                Row(Modifier.padding(top = 8.dp)) {
                    Text("Option chain", fontSize = 12.sp, lineHeight = 16.sp, color = Accent, modifier = Modifier.clickable { nav.chain(u) }.testTag("overview_chain_${u.name}"))
                    Text(" · ", fontSize = 12.sp, lineHeight = 16.sp, color = subInk())
                    Text("Futures", fontSize = 12.sp, lineHeight = 16.sp, color = Accent, modifier = Modifier.clickable { nav.futures(u) }.testTag("overview_futures_${u.name}"))
                }
            }
        }
    }
}

internal val DrawInk = Color(0xFF2B2D3A); private val TiePurple = Color(0xFF4A2FA0); internal val CoinGreen = Color(0xFF2A9A7F)

/**
 * The analysts card's drawing, in the card's own dp (a 64 dp square at its left): an outlined head and shoulders with a
 * purple tie, and in front a thumbs-up with a purple cuff and three speed lines behind it.
 */
private fun DrawScope.drawAdvisor() {
    val u = size.width / 64f; val line = Stroke(1.3f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun path(block: Path.() -> Unit) = Path().apply(block)
    drawCircle(DrawInk, 6.8f * u, Offset(33.2f * u, 21.9f * u), style = line)
    drawPath(path { moveTo(19.7f * u, 41f * u); cubicTo(19.7f * u, 33.6f * u, 23.2f * u, 29.3f * u, 29.4f * u, 29.3f * u) }, DrawInk, style = line)
    drawPath(path { moveTo(37f * u, 29.3f * u); lineTo(41.2f * u, 29.3f * u); cubicTo(45.1f * u, 29.3f * u, 47.7f * u, 32.1f * u, 47.7f * u, 35.6f * u)
        lineTo(47.7f * u, 46.6f * u); quadraticTo(47.7f * u, 48f * u, 46.3f * u, 48f * u); lineTo(34.6f * u, 48f * u) }, DrawInk, style = line)
    drawPath(path { moveTo(31f * u, 30.6f * u); lineTo(35.4f * u, 30.6f * u); lineTo(37.1f * u, 42.6f * u); lineTo(34.6f * u, 44.5f * u); close() }, TiePurple)
    val hand = path { moveTo(26.3f * u, 41.3f * u); lineTo(27.4f * u, 41.3f * u); lineTo(28f * u, 37.9f * u); quadraticTo(28.4f * u, 36.9f * u, 29.4f * u, 37.1f * u)
        quadraticTo(30.4f * u, 37.4f * u, 30.3f * u, 38.6f * u); lineTo(30f * u, 40.6f * u); lineTo(33.3f * u, 40.9f * u); quadraticTo(34.3f * u, 41f * u, 34.2f * u, 42f * u)
        lineTo(33.6f * u, 47.2f * u); quadraticTo(33.4f * u, 48.2f * u, 32.4f * u, 48.2f * u); lineTo(26.3f * u, 48.2f * u); close() }
    drawPath(hand, Color.White); drawPath(hand, DrawInk, style = line)
    drawRoundRect(TiePurple, Offset(21.9f * u, 40.3f * u), Size(4.6f * u, 8.1f * u), CornerRadius(0.6f * u))
    listOf(42.4f, 44.4f, 46.4f).forEach { y -> drawLine(DrawInk, Offset(16.6f * u, y * u), Offset(20.2f * u, y * u), 1.1f * u, StrokeCap.Round) }
}

/** Rewards' drawing on its 40 dp disc: a gift box in outline with a two-loop bow, under a solid blue band. */
private fun DrawScope.drawGift() {
    val u = size.width / 40f; val line = Stroke(1.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    drawPath(Path().apply { moveTo(20f * u, 14.3f * u); cubicTo(17.6f * u, 7.4f * u, 10.4f * u, 8.4f * u, 11.8f * u, 12.6f * u); cubicTo(12.4f * u, 14.2f * u, 15.5f * u, 14.3f * u, 20f * u, 14.3f * u)
        moveTo(20f * u, 14.3f * u); cubicTo(22.4f * u, 7.4f * u, 29.6f * u, 8.4f * u, 28.2f * u, 12.6f * u); cubicTo(27.6f * u, 14.2f * u, 24.5f * u, 14.3f * u, 20f * u, 14.3f * u) }, DrawInk, style = line)
    drawPath(Path().apply { moveTo(10.7f * u, 20.3f * u); lineTo(10.7f * u, 30.4f * u); quadraticTo(10.7f * u, 31.4f * u, 11.7f * u, 31.4f * u); lineTo(28.3f * u, 31.4f * u)
        quadraticTo(29.3f * u, 31.4f * u, 29.3f * u, 30.4f * u); lineTo(29.3f * u, 20.3f * u); moveTo(20f * u, 20.3f * u); lineTo(20f * u, 31.4f * u) }, DrawInk, style = line)
    drawRoundRect(Accent, Offset(8.1f * u, 14f * u), Size(23.8f * u, 6.3f * u), CornerRadius(1.6f * u))
}

/** Refer's drawing on its 40 dp disc: a green ₹ coin above an open hand drawn in outline, with a cuff line at the wrist. */
internal fun DrawScope.drawRefer() {
    val u = size.width / 40f; val line = Stroke(1.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round); val c = Offset(23.2f * u, 12.8f * u)
    drawCircle(CoinGreen, 5.6f * u, c)
    drawPath(Path().apply { val x = c.x; val y = c.y; val k = u
        moveTo(x - 2.1f * k, y - 2.5f * k); lineTo(x + 2.1f * k, y - 2.5f * k); moveTo(x - 2.1f * k, y - 1.1f * k); lineTo(x + 2.1f * k, y - 1.1f * k)
        moveTo(x - 1.6f * k, y - 2.5f * k); cubicTo(x + 1.6f * k, y - 2.5f * k, x + 1.6f * k, y + 0.6f * k, x - 1.6f * k, y + 0.6f * k); lineTo(x + 1.3f * k, y + 3.1f * k) },
        Color.White, style = Stroke(0.85f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(Path().apply {
        moveTo(9.1f * u, 26.1f * u); lineTo(14.7f * u, 21.3f * u); lineTo(22.6f * u, 21.3f * u); quadraticTo(24.2f * u, 21.3f * u, 24.2f * u, 23f * u)
        quadraticTo(24.2f * u, 24.8f * u, 22.5f * u, 24.8f * u); lineTo(17.6f * u, 24.8f * u)
        moveTo(23.4f * u, 23.6f * u); lineTo(31.6f * u, 19.8f * u); quadraticTo(33.7f * u, 19.2f * u, 33.1f * u, 21.3f * u); lineTo(24.6f * u, 30.4f * u)
        quadraticTo(24f * u, 31f * u, 23.1f * u, 31f * u); lineTo(12.8f * u, 31f * u); lineTo(9.1f * u, 26.1f * u)
        moveTo(6.5f * u, 25.4f * u); lineTo(14.3f * u, 33.2f * u) }, DrawInk, style = line)
}
