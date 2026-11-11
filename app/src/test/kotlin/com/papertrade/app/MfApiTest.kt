package com.papertrade.app

import com.papertrade.app.data.market.MfApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class MfApiTest {
    @Test fun parsesSearchWithNumericCodes() {
        val l = MfApi.parseSearch("""[{"schemeCode":122639,"schemeName":"Parag Parikh Flexi Cap Fund - Direct Plan - Growth"},{"schemeCode":1},{"schemeName":"x"}]""")
        assertEquals(1, l.size); assertEquals("122639", l[0].code); assertEquals("Parag Parikh Flexi Cap Fund - Direct Plan - Growth", l[0].name)
    }

    @Test fun parsesLatestNav() {
        val n = MfApi.parseLatest("""{"meta":{"scheme_code":122639},"data":[{"date":"09-11-2026","nav":"93.98840"}],"status":"SUCCESS"}""")
        assertEquals(0, BigDecimal("93.9884").compareTo(n.nav)); assertEquals(LocalDate.of(2026, 11, 9), n.date)
    }

    @Test fun missingNavIsAnError() {
        assertThrows(Exception::class.java) { MfApi.parseLatest("""{"meta":{},"data":[],"status":"SUCCESS"}""") }
        assertThrows(Exception::class.java) { MfApi.parseLatest("""{"data":[{"date":"09-11-2026","nav":"0"}]}""") }
    }
}
