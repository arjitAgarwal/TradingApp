package com.papertrade.app.data.db

import com.papertrade.app.data.market.InstrumentInfo
import com.papertrade.app.data.market.MarketQuote
import com.papertrade.core.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

private fun bd(s: String) = BigDecimal(s)
private fun ms(i: Instant) = i.toEpochMilli()
private fun inst(l: Long) = Instant.ofEpochMilli(l)

fun Wallet.toEntity() = WalletEntity(1, startingCapital.toPlainString(), cash.toPlainString(), reservedMargin.toPlainString())
fun WalletEntity.toDomain() = Wallet(bd(startingCapital), bd(cash), bd(reservedMargin))

fun Order.toEntity() = OrderEntity(id, instrumentId, side.name, type.name, quantity, limitPrice?.toPlainString(), filledQuantity, avgFillPrice?.toPlainString(),
    status.name, ms(createdAt), ms(updatedAt), executedAt?.let(::ms), rejectReason, lastQuoteTs?.let(::ms), fillSeq)
fun OrderEntity.toDomain() = Order(id, instrumentId, Side.valueOf(side), OrderType.valueOf(type), quantity, limitPrice?.let(::bd), filledQuantity, avgFillPrice?.let(::bd),
    OrderStatus.valueOf(status), inst(createdAt), inst(updatedAt), executedAt?.let(::inst), rejectReason, lastQuoteTs?.let(::inst), fillSeq)

fun Fill.toEntity() = FillEntity(id, orderId, instrumentId, side.name, quantity, price.toPlainString(), charges.toPlainString(), realizedGross.toPlainString(), ms(time), quoteSource.name)
fun FillEntity.toDomain() = Fill(id, orderId, instrumentId, Side.valueOf(side), quantity, bd(price), bd(charges), bd(realizedGross), inst(time), QuoteSource.valueOf(quoteSource))

fun Position.toEntity() = PositionEntity(instrumentId, quantity, avgPrice.toPlainString(), realizedGross.toPlainString(), charges.toPlainString())
fun PositionEntity.toDomain() = Position(instrumentId, quantity, bd(avgPrice), bd(realizedGross), bd(charges))

fun LedgerEntry.toEntity(seq: Long) = LedgerEntity(id, type.name, amount.toPlainString(), refFillId, ms(time), note, seq)
fun LedgerEntity.toDomain() = LedgerEntry(id, LedgerType.valueOf(type), bd(amount), refFillId, inst(time), note)

fun InstrumentInfo.toEntity(now: Instant) = InstrumentEntity(symbol, name, segment.name, lotSize, tickSize.toPlainString(), expiry?.toString(), strike?.toPlainString(), optionType?.name, underlying, ms(now))
fun InstrumentEntity.toInfo() = InstrumentInfo(symbol, name, Segment.valueOf(segment), lotSize, bd(tickSize), expiry?.let(LocalDate::parse), strike?.let(::bd), optionType?.let(OptionType::valueOf), underlying)

fun MarketQuote.toCacheEntity() = QuoteCacheEntity(symbol, ltp.toPlainString(), bid?.toPlainString(), ask?.toPlainString(), prevClose?.toPlainString(), change?.toPlainString(),
    changePct?.toPlainString(), open?.toPlainString(), high?.toPlainString(), low?.toPlainString(), ms(timestamp), source.name)

/** Restored quotes are ALWAYS tagged CACHED so they are displayed with their original time and can never fill a market order. */
fun QuoteCacheEntity.toCachedQuote() = MarketQuote(symbol, bd(ltp), open?.let(::bd), high?.let(::bd), low?.let(::bd), prevClose?.let(::bd), change?.let(::bd), changePct?.let(::bd),
    bid?.let(::bd), ask?.let(::bd), null, null, null, inst(timestamp), QuoteSource.CACHED)

fun MarketQuote.toCore() = Quote(symbol, ltp, bid, ask, timestamp, source)
