package com.papertrade.app.data.market

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64

/**
 * FYERS Data Socket v3 ("HSM") binary protocol, ported from FYERS' official Python SDK (fyers-apiv3 3.2.0,
 * FyersWebsocket/data_ws.py and map.json). Frames are big-endian: u16 length, u8 type, u8 field count, then fields of
 * (u8 id, u16 length, bytes).
 *
 * Flow: connect to [URL] -> send [auth] -> send [fullMode] -> send [subscribe] with HSM topics ("sf|nse_fo|<token>" for
 * scrips, "if|nse_cm|Nifty 50" for indices) -> receive a snapshot (83) per topic, then partial updates (85) carrying only
 * changed fields. Ping ([PING]) every 10 s; acknowledge every Nth data message when the server asks for it.
 */
internal object FyersHsm {
    const val URL = "wss://socket.fyers.in/hsm/v1-5/prod"
    const val SOURCE = "PaperTrade-Android"
    private const val CHANNEL = 11
    val PING = byteArrayOf(0, 1, 11)

    /** FYERS segment code (first 4 digits of a fyToken) to HSM segment name. */
    val SEGMENTS = mapOf("1010" to "nse_cm", "1011" to "nse_fo", "1120" to "mcx_fo", "1210" to "bse_cm", "1012" to "cde_fo",
        "1211" to "bse_fo", "1212" to "bcs_fo", "1020" to "nse_com")
    /** Index symbol to the exchange's index name, which is the HSM token for indices (map.json "index_dict"). */
    val INDEX_NAMES = mapOf("NSE:NIFTY50-INDEX" to "Nifty 50", "BSE:SENSEX-INDEX" to "SENSEX", "NSE:NIFTYBANK-INDEX" to "Nifty Bank")

    /** HSM topic for [symbol] given its fyToken, or null for an unknown segment. */
    fun topic(symbol: String, fyToken: String): String? {
        val segment = SEGMENTS[fyToken.take(4)] ?: return null
        return if (symbol.endsWith("-INDEX")) "if|$segment|${INDEX_NAMES[symbol] ?: symbol.substringAfter(':').substringBefore('-')}"
        else fyToken.drop(10).takeIf { it.isNotEmpty() }?.let { "sf|$segment|$it" }
    }

    /** The socket key is the "hsm_key" claim inside the access token (a JWT); null if the token isn't one. */
    fun hsmKey(accessToken: String): String? = runCatching {
        val payload = accessToken.substringAfter(':').split('.')[1]
        val json = String(Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '=')))
        Regex("\"hsm_key\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)
    }.getOrNull()

    fun auth(hsmKey: String, source: String = SOURCE): ByteArray = frame(1, listOf(1 to hsmKey.toByteArray(), 2 to "P".toByteArray(), 3 to byteArrayOf(1), 4 to source.toByteArray()))

    /** Full mode on our channel: every field, not just LTP. */
    fun fullMode(): ByteArray = ByteArrayOutputStream().apply {
        u16(0); write(12); write(2)
        write(1); u16(8); write(ByteBuffer.allocate(8).putLong(1L shl CHANNEL).array())
        write(2); u16(1); write(70)
    }.toByteArray()

    fun subscribe(topics: List<String>): ByteArray {
        val scrips = ByteArrayOutputStream().apply { u16(topics.size); topics.forEach { t -> val b = t.toByteArray(Charsets.US_ASCII); write(b.size); write(b) } }.toByteArray()
        return ByteArrayOutputStream().apply {
            u16(scrips.size + 9); write(4); write(2)
            write(1); u16(scrips.size); write(scrips)
            write(2); u16(1); write(CHANNEL)
        }.toByteArray()
    }

    fun ack(messageNumber: Int): ByteArray = ByteArrayOutputStream().apply {
        u16(9); write(3); write(1); write(1); u16(4); write(ByteBuffer.allocate(4).putInt(messageNumber).array())
    }.toByteArray()

    private fun frame(type: Int, fields: List<Pair<Int, ByteArray>>): ByteArray {
        val body = ByteArrayOutputStream().apply { write(type); write(fields.size); fields.forEach { (id, v) -> write(id); u16(v.size); write(v) } }.toByteArray()
        return ByteArrayOutputStream().apply { u16(body.size); write(body) }.toByteArray()
    }

    private fun ByteArrayOutputStream.u16(v: Int) { write(v shr 8 and 0xFF); write(v and 0xFF) }
}

/**
 * Stateful decoder for one socket connection: remembers each topic's name, scale and last values from its snapshot so the
 * partial updates that follow can be applied. Not thread-safe; feed it from the socket's single reader thread.
 */
internal class FyersHsmDecoder {
    /** One topic's current state, prices already scaled to rupees. */
    data class Update(val topic: String, val ltp: Double?, val prevClose: Double?, val open: Double?, val high: Double?, val low: Double?,
                      val bid: Double?, val ask: Double?, val volume: Long?, val oi: Long?)

    sealed interface Event {
        data class Auth(val ok: Boolean) : Event
        data class Data(val updates: List<Update>, val ack: ByteArray?) : Event
        data class ServerMessage(val code: Int, val message: String) : Event
        data object Other : Event
    }

    private class Topic(val name: String, val index: Boolean, val values: IntArray, val scale: Double)
    private val topics = HashMap<Int, Topic>()
    private var ackEvery = 0; private var sinceAck = 0

    fun decode(bytes: ByteArray): Event {
        if (bytes.size < 3) return Event.Other
        val b = ByteBuffer.wrap(bytes)
        return when (bytes[2].toInt() and 0xFF) {
            1 -> auth(b)
            6 -> data(b)
            51 -> custom(b)
            else -> Event.Other
        }
    }

    private fun auth(b: ByteBuffer): Event {
        b.position(5); val len = u16(b); val ok = String(ByteArray(len).also { b.get(it) }) == "K"
        if (b.remaining() >= 7) { b.get(); u16(b); ackEvery = b.int }
        return Event.Auth(ok)
    }

    private fun data(b: ByteBuffer): Event {
        b.position(3); val messageNumber = b.int
        var ack: ByteArray? = null
        if (ackEvery > 0 && ++sinceAck >= ackEvery) { sinceAck = 0; ack = FyersHsm.ack(messageNumber) }
        val out = ArrayList<Update>()
        repeat(u16(b)) {
            when (b.get().toInt() and 0xFF) {
                83 -> snapshot(b)?.let(out::add)
                85 -> partial(b)?.let(out::add)
                76 -> { val t = topics[u16(b)]; val v = b.int; if (t != null && v != MISSING) { t.values[0] = v; out += t.update() } }
                else -> return Event.Data(out, ack) // unknown record: its length is unknown, so stop here rather than misread
            }
        }
        return Event.Data(out, ack)
    }

    private fun snapshot(b: ByteBuffer): Update? {
        val id = u16(b); val name = String(ByteArray(b.get().toInt() and 0xFF).also { b.get(it) })
        val values = IntArray(b.get().toInt() and 0xFF) { b.int }
        b.position(b.position() + 2)
        val multiplier = u16(b); val precision = b.get().toInt() and 0xFF
        repeat(3) { val len = b.get().toInt() and 0xFF; b.position(b.position() + len) } // exchange, exchange token, symbol
        if (name.startsWith("dp")) return null // depth topics aren't subscribed
        val t = Topic(name, name.startsWith("if"), values, Math.pow(10.0, precision.toDouble()) * multiplier.coerceAtLeast(1))
        topics[id] = t
        return t.update()
    }

    private fun partial(b: ByteBuffer): Update? {
        val t = topics[u16(b)]; val n = b.get().toInt() and 0xFF
        var changed = false
        for (i in 0 until n) { val v = b.int; if (t != null && i < t.values.size && v != MISSING && t.values[i] != v) { t.values[i] = v; changed = true } }
        return if (t != null && changed) t.update() else null
    }

    private fun Topic.update(): Update {
        fun raw(i: Int) = values.getOrNull(i)?.takeIf { it != MISSING }
        fun price(i: Int) = raw(i)?.let { it / scale }
        // Field order from map.json: indices use index_val, scrips use data_val.
        return if (index) Update(name, price(0), price(1), price(5), price(3), price(4), null, null, null, null)
        else Update(name, price(0), price(20), price(19), price(14), price(13), price(6)?.takeIf { it > 0 }, price(7)?.takeIf { it > 0 },
            raw(1)?.toLong(), raw(12)?.toLong()?.takeIf { it > 0 })
    }

    private fun custom(b: ByteBuffer): Event {
        b.position(3); var code = 0; var msg = ""
        repeat(b.get().toInt() and 0xFF) {
            val id = b.get().toInt() and 0xFF; val len = u16(b); val v = ByteArray(len).also { b.get(it) }
            if (id == 1 && len == 4) code = ByteBuffer.wrap(v).int else if (id == 2) msg = String(v)
        }
        return Event.ServerMessage(code, msg)
    }

    private fun u16(b: ByteBuffer) = b.short.toInt() and 0xFFFF

    private companion object { const val MISSING = Int.MIN_VALUE }
}
