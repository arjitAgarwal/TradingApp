package com.papertrade.app

import com.papertrade.app.data.market.FyersHsm
import com.papertrade.app.data.market.FyersHsmDecoder
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** FYERS Data Socket (HSM) protocol, checked against a real message captured from FYERS on 22 Oct 2026. */
class FyersFeedTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Snapshot (83) for NIFTY 26 Oct 22100 CE, NIFTY 50 and SENSEX, exactly as received. */
    private val snapshot = hex(
        "012b060000000300035300000f73667c6e73655f666f7c3531333132150000dd2c000142356ad991ce6ad991d200000145000001040000dc370000dcfa000000410000bef0" +
        "0000a8570000d61f00019cda0000bb580000eff10001011c00000000000000050001d6550000d3ef000100e50026000102066e73655f666f053531333132154e53453a4e49" +
        "46545932364f4354323231303043455300011269667c6e73655f636d7c4e6966747920353006002284b80022ceaf6ad991d30022a23b00226a7d00229e67001c000102066e" +
        "73655f636d084e69667479203530084e696674792035305300021069667c6273655f636d7c53454e53455806006fbd8c007078d96ad991d2007008dc006f659f006fe9bd00" +
        "18000102066273655f636d0653454e5345580653454e534558")

    @Test fun decodesRealSnapshot() {
        val e = FyersHsmDecoder().decode(snapshot) as FyersHsmDecoder.Event.Data
        val byTopic = e.updates.associateBy { it.topic }
        assertEquals(setOf("sf|nse_fo|51312", "if|nse_cm|Nifty 50", "if|bse_cm|SENSEX"), byTopic.keys)
        val opt = byTopic.getValue("sf|nse_fo|51312")
        assertEquals(566.20, opt.ltp!!, 1e-9); assertEquals(657.65, opt.prevClose!!, 1e-9)
        assertEquals(563.75, opt.bid!!, 1e-9); assertEquals(565.70, opt.ask!!, 1e-9)
        assertEquals(542.55, opt.open!!, 1e-9); assertEquals(614.25, opt.high!!, 1e-9); assertEquals(479.60, opt.low!!, 1e-9)
        assertEquals(82_485L, opt.volume); assertEquals(105_690L, opt.oi)
        val nifty = byTopic.getValue("if|nse_cm|Nifty 50")
        assertEquals(22_622.00, nifty.ltp!!, 1e-9); assertEquals(22_811.35, nifty.prevClose!!, 1e-9)
        assertNull(nifty.bid)
        assertNull(e.ack) // no ack interval negotiated
    }

    @Test fun partialUpdateChangesOnlySentFields() {
        val d = FyersHsmDecoder(); d.decode(snapshot)
        // 85 for topic 0 (the option): 1 field, ltp = 570.00
        val partial = hex("000f06000000040001" + "55" + "0000" + "01" + "0000dea8")
        val u = (d.decode(partial) as FyersHsmDecoder.Event.Data).updates.single()
        assertEquals(570.00, u.ltp!!, 1e-9)
        assertEquals(657.65, u.prevClose!!, 1e-9) // kept from the snapshot
        // Same value again: nothing changed, nothing emitted.
        assertTrue((d.decode(partial) as FyersHsmDecoder.Event.Data).updates.isEmpty())
    }

    @Test fun acknowledgesEveryNthMessageWhenAsked() {
        val d = FyersHsmDecoder()
        // Auth response: field 1 "K", field 2 ack interval 2.
        assertEquals(FyersHsmDecoder.Event.Auth(true), d.decode(hex("000e01020100014b02000400000002")))
        assertNull((d.decode(snapshot) as FyersHsmDecoder.Event.Data).ack)
        val second = d.decode(hex("000f06000000040001" + "55" + "0000" + "01" + "0000dea8")) as FyersHsmDecoder.Event.Data
        assertArrayEquals(hex("0009030101000400000004"), second.ack)
    }

    @Test fun authFrameMatchesSdkLayout() {
        val f = FyersHsm.auth("KEY", "SRC")
        // u16 len (= SDK's 18 + key + source - 2), type 1, 4 fields: (1,"KEY") (2,"P") (3,0x01) (4,"SRC")
        assertArrayEquals(hex("0016" + "0104" + "0100034b4559" + "02000150" + "03000101" + "0400035352" + "43"), f)
    }

    @Test fun subscribeFrameListsTopics() {
        val f = FyersHsm.subscribe(listOf("if|nse_cm|Nifty 50"))
        assertEquals(4, f[2].toInt()); assertEquals(2, f[3].toInt())
        assertEquals(f.size - 2, ((f[0].toInt() and 0xFF) shl 8) or (f[1].toInt() and 0xFF))
        assertTrue(String(f, Charsets.US_ASCII).contains("if|nse_cm|Nifty 50"))
        assertEquals(11, f.last().toInt()) // channel
    }

    @Test fun topicsAndHsmKey() {
        assertEquals("sf|nse_fo|51312", FyersHsm.topic("NSE:NIFTY26OCT22100CE", "101126102751312"))
        assertEquals("if|nse_cm|Nifty 50", FyersHsm.topic("NSE:NIFTY50-INDEX", "101000000026000"))
        assertEquals("if|bse_cm|SENSEX", FyersHsm.topic("BSE:SENSEX-INDEX", "121000000001"))
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":1,"hsm_key":"abc123"}""".toByteArray())
        assertEquals("abc123", FyersHsm.hsmKey("APPID:hdr.$payload.sig"))
        assertNull(FyersHsm.hsmKey("not-a-jwt"))
    }
}
