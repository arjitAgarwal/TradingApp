package com.papertrade.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class IndianFormatTest {
    private fun f(s: String, d: Int = 2) = IndianFormat.group(BigDecimal(s), d)
    @Test fun grouping() {
        assertEquals("10,00,000.00", f("1000000")); assertEquals("999.50", f("999.5")); assertEquals("1,234.00", f("1234"))
        assertEquals("12,34,567.89", f("1234567.891")); assertEquals("1,00,00,000.00", f("10000000")); assertEquals("0.00", f("0"))
        assertEquals("-1,23,456.00", f("-123456")); assertEquals("25,000", f("25000", 0)); assertEquals("0.05", f("0.05"))
    }
}
