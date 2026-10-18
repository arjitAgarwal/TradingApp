package com.papertrade.core

import java.math.BigDecimal
import java.math.RoundingMode

object IndianFormat {
    /** 1000000 -> "10,00,000.00" (lakh/crore grouping). Rounds HALF_UP. */
    fun group(v: BigDecimal, decimals: Int = 2): String {
        val s = v.setScale(decimals, RoundingMode.HALF_UP)
        val neg = s.signum() < 0
        val plain = s.abs().toPlainString()
        val intPart = plain.substringBefore('.')
        val frac = if (plain.contains('.')) "." + plain.substringAfter('.') else ""
        val grouped = if (intPart.length <= 3) intPart else intPart.dropLast(3).reversed().chunked(2).joinToString(",").reversed() + "," + intPart.takeLast(3)
        return (if (neg) "-" else "") + grouped + frac
    }
}
