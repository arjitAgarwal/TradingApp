package com.papertrade.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

/** All money/price maths uses BigDecimal. Prices keep 6 dp internally; cash amounts are rounded to 2 dp. */
object Dec {
    val ZERO: BigDecimal = BigDecimal.ZERO
    const val PRICE_SCALE = 6
    const val MONEY_SCALE = 2
    fun bd(s: String) = BigDecimal(s)
    fun bd(i: Int) = BigDecimal(i)
    fun price(v: BigDecimal): BigDecimal = v.setScale(PRICE_SCALE, RoundingMode.HALF_EVEN)
    fun money(v: BigDecimal): BigDecimal = v.setScale(MONEY_SCALE, RoundingMode.HALF_UP)
    /** Safe division. NEVER use Kotlin's `/` on BigDecimal: it keeps the dividend's scale and silently truncates (10/10000 == 0). */
    fun div(a: BigDecimal, b: BigDecimal): BigDecimal = a.divide(b, 12, RoundingMode.HALF_EVEN)
}

enum class Segment { EQUITY, FUTURES, OPTION }
enum class OptionType { CE, PE }
enum class Side(val sign: Int) { BUY(1), SELL(-1) }
enum class OrderType { MARKET, LIMIT }
enum class OrderStatus { CREATED, OPEN, PARTIALLY_FILLED, FILLED, CANCELLED, REJECTED;
    val isTerminal get() = this == FILLED || this == CANCELLED || this == REJECTED
    val isActive get() = this == OPEN || this == PARTIALLY_FILLED
}

/** Where a quote came from. Only LIVE quotes may be described as live in the UI. */
enum class QuoteSource { LIVE, DELAYED, CACHED, DEMO, TEST }

/**
 * Tradable instrument. [multiplier] is the lot size for derivatives and 1 for equity.
 * Order/position quantity is in LOTS for derivatives and shares for equity, so
 * P&L = price delta x quantity x multiplier everywhere.
 * Metadata (lot size, tick, expiry, strike) MUST come from provider instrument metadata, never be hardcoded.
 */
data class Instrument(
    val id: String,
    val symbol: String,
    val name: String,
    val segment: Segment,
    val multiplier: Int,
    val tickSize: BigDecimal,
    val expiry: LocalDate? = null,
    val strike: BigDecimal? = null,
    val optionType: OptionType? = null,
    val underlyingId: String? = null,
) {
    init {
        require(multiplier > 0) { "multiplier must be positive" }
        require(tickSize.signum() > 0) { "tickSize must be positive" }
        if (segment == Segment.OPTION) require(strike != null && optionType != null && expiry != null)
        if (segment == Segment.FUTURES) require(expiry != null)
    }
}

data class Quote(
    val instrumentId: String,
    val ltp: BigDecimal,
    val bid: BigDecimal? = null,
    val ask: BigDecimal? = null,
    val timestamp: Instant,
    val source: QuoteSource,
)

data class OrderRequest(
    val instrumentId: String,
    val side: Side,
    val type: OrderType,
    val quantity: Int,
    val limitPrice: BigDecimal? = null,
)

data class Order(
    val id: String,
    val instrumentId: String,
    val side: Side,
    val type: OrderType,
    val quantity: Int,
    val limitPrice: BigDecimal?,
    val filledQuantity: Int = 0,
    val avgFillPrice: BigDecimal? = null,
    val status: OrderStatus = OrderStatus.CREATED,
    val createdAt: Instant,
    val updatedAt: Instant,
    val executedAt: Instant? = null,
    val rejectReason: String? = null,
    /** Newest quote timestamp already evaluated against this order; quotes not newer are ignored (no refill on refresh). */
    val lastQuoteTs: Instant? = null,
    val fillSeq: Int = 0,
) { val remaining get() = quantity - filledQuantity }

data class Fill(
    val id: String,
    val orderId: String,
    val instrumentId: String,
    val side: Side,
    val quantity: Int,
    val price: BigDecimal,
    val charges: BigDecimal,
    val realizedGross: BigDecimal,
    val time: Instant,
    val quoteSource: QuoteSource,
)

/** Net position per instrument. Equity positions are DELIVERY holdings (long only). */
data class Position(
    val instrumentId: String,
    /** Signed quantity: >0 long, <0 short, 0 closed. */
    val quantity: Int = 0,
    val avgPrice: BigDecimal = Dec.ZERO,
    val realizedGross: BigDecimal = Dec.ZERO,
    val charges: BigDecimal = Dec.ZERO,
) {
    val isOpen get() = quantity != 0
    val realizedNet get() = realizedGross - charges
}

enum class LedgerType { INITIAL_CAPITAL, TRADE_VALUE, FUTURES_SETTLEMENT, CHARGES }

/** Append-only. The sum of all amounts is always equal to the cash balance. */
data class LedgerEntry(
    val id: String,
    val type: LedgerType,
    val amount: BigDecimal,
    val refFillId: String?,
    val time: Instant,
    val note: String = "",
)

data class Wallet(
    val startingCapital: BigDecimal,
    val cash: BigDecimal,
    /** Simulated margin earmarked for futures and short options. Not an official broker margin. */
    val reservedMargin: BigDecimal = Dec.ZERO,
) { val available get() = cash - reservedMargin }

data class AccountState(
    val wallet: Wallet,
    val orders: Map<String, Order> = emptyMap(),
    val positions: Map<String, Position> = emptyMap(),
    val fills: List<Fill> = emptyList(),
    val ledger: List<LedgerEntry> = emptyList(),
) {
    companion object {
        fun initial(capital: BigDecimal, now: Instant) = AccountState(
            wallet = Wallet(capital, capital),
            ledger = listOf(LedgerEntry("initial", LedgerType.INITIAL_CAPITAL, capital, null, now, "Starting virtual capital")),
        )
    }
}
