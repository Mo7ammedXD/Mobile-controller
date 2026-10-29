package com.mobilecontroller.client

import com.mobilecontroller.client.protocol.Protocol
import com.mobilecontroller.client.ui.GameModes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROTOCOL.md §9 golden vectors and the §2 validation rules, as seen by the client. */
class ProtocolTest {
    @Test
    fun i1_neutralInput() {
        val b = ByteArray(Protocol.INPUT_SIZE)
        Protocol.writeInput(b, 0, 0, 0, 0, 0, 0, 0, 0)
        assertArrayEquals(hex("A5 11 00 00 00 00 00 00 00 00 00 00 00 00 00 00"), b)
    }

    @Test
    fun i2_mixedInput() {
        val b = ByteArray(Protocol.INPUT_SIZE)
        Protocol.writeInput(b, 0x1234, Protocol.A or Protocol.DPAD_UP, 0, 255, 32767, -32767, -1, 256)
        assertArrayEquals(hex("A5 11 34 12 01 10 00 FF FF 7F 01 80 FF FF 00 01"), b)
    }

    @Test
    fun i3_extremes() {
        val b = ByteArray(Protocol.INPUT_SIZE)
        Protocol.writeInput(b, 0xFFFF, 0xF7FF, 128, 1, -32768, 12345, -12345, 0)
        assertArrayEquals(hex("A5 11 FF FF FF F7 80 01 00 80 39 30 C7 CF 00 00"), b)
    }

    @Test
    fun i4_reservedBitIsMaskedOnDecodeAndNeverEncoded() {
        val raw = hex("A5 11 01 00 FF FF 00 00 00 00 00 00 00 00 00 00")
        assertEquals(Protocol.TYPE_INPUT, Protocol.validate(raw, raw.size, serverToClient = false))
        assertEquals(0xF7FF, Protocol.inputButtons(raw))
        val b = ByteArray(Protocol.INPUT_SIZE)
        Protocol.writeInput(b, 1, 0xFFFF, 0, 0, 0, 0, 0, 0)
        assertArrayEquals(hex("A5 11 01 00 FF F7 00 00 00 00 00 00 00 00 00 00"), b)
    }

    @Test
    fun inputDecodesToItsFields() {
        val b = hex("A5 11 34 12 01 10 00 FF FF 7F 01 80 FF FF 00 01")
        assertEquals(0x1234, Protocol.u16(b, 2))
        assertEquals(0x1001, Protocol.inputButtons(b))
        assertEquals(0, Protocol.u8(b, 6))
        assertEquals(255, Protocol.u8(b, 7))
        assertEquals(32767, Protocol.i16(b, 8))
        assertEquals(-32767, Protocol.i16(b, 10))
        assertEquals(-1, Protocol.i16(b, 12))
        assertEquals(256, Protocol.i16(b, 14))
    }

    @Test
    fun p1_ping() {
        val b = ByteArray(Protocol.PING_SIZE)
        Protocol.writePing(b, 0x0102, 0xDEADBEEF.toInt())
        assertArrayEquals(hex("A5 12 02 01 EF BE AD DE"), b)
    }

    @Test
    fun p2_pong() {
        val b = hex("A5 1A 02 01 EF BE AD DE")
        assertEquals(Protocol.TYPE_PONG, Protocol.validate(b, b.size, serverToClient = true))
        assertEquals(0x0102, Protocol.u16(b, Protocol.PONG_ID))
        assertEquals(0xDEADBEEFL, Protocol.u32(b, Protocol.PONG_CLIENT_TIME))
    }

    @Test
    fun p3_bye() {
        val b = ByteArray(Protocol.BYE_SIZE)
        Protocol.writeBye(b)
        assertArrayEquals(hex("A5 13"), b)
    }

    @Test
    fun p4_discover() {
        val b = ByteArray(Protocol.DISCOVER_SIZE)
        Protocol.writeDiscover(b, 0xBEEF)
        assertArrayEquals(hex("A5 14 EF BE"), b)
    }

    @Test
    fun p5_discoverReply() {
        val b = hex("A5 1C EF BE 01 04 02 50 43")
        assertEquals(Protocol.TYPE_DISCOVER_REPLY, Protocol.validate(b, b.size, serverToClient = true))
        assertEquals(0xBEEF, Protocol.u16(b, Protocol.REPLY_NONCE))
        assertEquals(1, Protocol.u8(b, Protocol.REPLY_PADS_IN_USE))
        assertEquals(4, Protocol.u8(b, Protocol.REPLY_MAX_PADS))
        assertEquals("PC", Protocol.replyName(b))
    }

    @Test
    fun p6_feedback() {
        val b = hex("A5 19 FF 40 00 01 34 12")
        assertEquals(Protocol.TYPE_FEEDBACK, Protocol.validate(b, b.size, serverToClient = true))
        assertEquals(255, Protocol.u8(b, Protocol.FEEDBACK_LARGE_MOTOR))
        assertEquals(64, Protocol.u8(b, Protocol.FEEDBACK_SMALL_MOTOR))
        assertEquals(0, Protocol.u8(b, Protocol.FEEDBACK_PLAYER))
        assertEquals(Protocol.FLAG_PAD_ATTACHED, Protocol.u8(b, Protocol.FEEDBACK_FLAGS))
        assertEquals(0x1234, Protocol.u16(b, Protocol.FEEDBACK_LAST_SEQ))
    }

    @Test
    fun p7_discoverReplyWithTruncatedName() {
        val b = hex("A5 1C 00 00 00 04 1F" + " 41".repeat(31))
        assertEquals(38, b.size)
        assertEquals(Protocol.TYPE_DISCOVER_REPLY, Protocol.validate(b, b.size, serverToClient = true))
        assertEquals("A".repeat(31), Protocol.replyName(b))
    }

    @Test
    fun r1_toR6_rejected() {
        assertInvalid("A5 11 00 00 00 00 00 00 00 00 00 00 00 00 00", serverToClient = false) // R1 15 bytes
        assertInvalid("A5 21 00 00 00 00 00 00 00 00 00 00 00 00 00 00", serverToClient = false) // R2 version 2
        assertInvalid("A4 11 00 00 00 00 00 00 00 00 00 00 00 00 00 00", serverToClient = false) // R3 magic
        assertInvalid("A5 16 00 00", serverToClient = false) // R4 unknown type
        assertInvalid("A5 19 FF 40 00 01 34 12", serverToClient = false) // R5 FEEDBACK at the server
        assertInvalid("A5 13 00", serverToClient = false) // R6 BYE length
    }

    @Test
    fun clientDropsWhatItMustNotAccept() {
        // Wrong direction: client -> server types arriving at the client.
        assertInvalid("A5 11 00 00 00 00 00 00 00 00 00 00 00 00 00 00")
        assertInvalid("A5 12 02 01 EF BE AD DE")
        assertInvalid("A5 13")
        assertInvalid("A5 14 EF BE")
        // Wrong lengths.
        assertInvalid("A5 19 FF 40 00 01 34")
        assertInvalid("A5 19 FF 40 00 01 34 12 00")
        assertInvalid("A5 1A 02 01 EF BE AD")
        assertInvalid("A5 1C EF BE 01 04 02 50") // N = 2 but only one name byte
        assertInvalid("A5 1C EF BE 01 04 02 50 43 44") // one byte too many
        assertInvalid("A5 1C EF BE 01 04") // shorter than the 7-byte header
        assertInvalid("A5 1C 00 00 00 04 21" + " 41".repeat(33)) // N = 33 > 32
        // Header problems.
        assertInvalid("")
        assertInvalid("A5")
        assertInvalid("A4 19 FF 40 00 01 34 12")
        assertInvalid("A5 29 FF 40 00 01 34 12") // version 2
        assertInvalid("A5 09 FF 40 00 01 34 12") // version 0
        for (type in intArrayOf(0x0, 0x8, 0xE, 0xF)) {
            assertInvalid("A5 %02X FF 40 00 01 34 12".format(0x10 or type))
        }
    }

    // --- §9.3 P10..P12, §9.5 STATUS, §9.6 COMMAND -----------------------------------------------

    @Test
    fun p10_p12_newFeedbackFlags() {
        val ds4 = hex("A5 19 00 00 FF 49 05 00")
        assertEquals(Protocol.TYPE_FEEDBACK, Protocol.validate(ds4, ds4.size, serverToClient = true))
        assertEquals(
            Protocol.FLAG_STATUS_ACK or Protocol.FLAG_TARGET_DS4 or Protocol.FLAG_PAD_ATTACHED,
            Protocol.u8(ds4, Protocol.FEEDBACK_FLAGS),
        )
        assertEquals(Protocol.FLAG_STATUS_ACK or Protocol.FLAG_AWAITING_APPROVAL, Protocol.u8(hex("A5 19 00 00 FF 50 00 00"), Protocol.FEEDBACK_FLAGS))
        assertEquals(Protocol.FLAG_STATUS_ACK or Protocol.FLAG_DENIED, Protocol.u8(hex("A5 19 00 00 FF 60 00 00"), Protocol.FEEDBACK_FLAGS))
    }

    private fun status(name: String, profile: String, id: Long = 1L, battery: Int = Protocol.BATTERY_UNKNOWN, flags: Int = 0,
        rtt: Int = Protocol.RTT_UNKNOWN, rate: Int = 0, output: Int = Protocol.OUTPUT_NONE, version: Int = 0): ByteArray {
        val b = ByteArray(Protocol.MAX_STATUS_SIZE)
        val n = Protocol.encodeName(name)
        val p = Protocol.encodeName(profile)
        val length = Protocol.writeStatus(b, id, battery, flags, rtt, rate, output, version, n, n.size, p, p.size)
        return b.copyOf(length)
    }

    @Test
    fun s1_s2_s4_status() {
        val s1 = status("Pixel", "Shooter", 0x0123456789ABCDEFL, 87, Protocol.STATUS_CHARGING, 42, 120, Protocol.OUTPUT_DS4, 2)
        assertArrayEquals(hex("A5 15 EF CD AB 89 67 45 23 01 57 01 2A 00 78 02 02 00 00 05 07 50 69 78 65 6C 53 68 6F 6F 74 65 72"), s1)
        assertEquals(Protocol.TYPE_STATUS, Protocol.validate(s1, s1.size, serverToClient = false))
        assertArrayEquals(hex("A5 15 01 00 00 00 00 00 00 00 FF 00 FF FF 00 00 00 00 00 00 00"), status("", ""))
        assertArrayEquals(
            hex("A5 15 01 00 00 00 00 00 00 00 FF 00 FF FF 00 00 00 00 00 1F 00" + " 41".repeat(31)),
            status("A".repeat(31) + "é", ""),
        )
        // RTT saturates instead of wrapping.
        assertEquals(Protocol.RTT_UNKNOWN, Protocol.u16(status("", "", rtt = 1_000_000), 12))
        // A 4-byte emoji that does not fit whole is dropped whole.
        assertEquals(29, Protocol.encodeName("0123456789abcdef0123456789abc😀").size)
    }

    @Test
    fun s3_statusWithAnExtensionIsValid() {
        val s3 = hex("A5 15 01 00 00 00 00 00 00 00 FF 00 FF FF 00 00 00 00 02 01 00 AA BB 58")
        assertEquals(Protocol.TYPE_STATUS, Protocol.validate(s3, s3.size, serverToClient = false))
    }

    @Test
    fun rs1_rs6_malformedStatus() {
        assertInvalid("A5 15 00 00", serverToClient = false)
        assertInvalid("A5 15 01 00 00 00 00 00 00 00 FF 00 FF FF 00 00 00 00 00 00 00 00", serverToClient = false)
        assertInvalid("A5 15 EF CD AB 89 67 45 23 01 57 01 2A 00 78 02 02 00 00 05 07 50 69 78 65 6C 53 68 6F 6F 74 65", serverToClient = false)
        assertInvalid("A5 15 01 00 00 00 00 00 00 00 FF 00 FF FF 00 00 00 00 00 21 00" + " 41".repeat(33), serverToClient = false)
        assertInvalid("A5 15 00 00 00 00 00 00 00 00 FF 00 FF FF 00 00 00 00 00 00 00", serverToClient = false)
        // RS6: a STATUS arriving at the phone.
        assertInvalid("A5 15 EF CD AB 89 67 45 23 01 57 01 2A 00 78 02 02 00 00 05 07 50 69 78 65 6C 53 68 6F 6F 74 65 72")
    }

    @Test
    fun c1_c2_commands() {
        val c1 = hex("A5 1D 01 02 07 1E")
        assertEquals(Protocol.TYPE_COMMAND, Protocol.validate(c1, c1.size, serverToClient = true))
        assertEquals(Protocol.COMMAND_IDENTIFY, Protocol.u8(c1, Protocol.COMMAND_OP))
        assertEquals(2, Protocol.u8(c1, Protocol.COMMAND_ARG_LENGTH))
        assertEquals(7, Protocol.u8(c1, Protocol.COMMAND_ARGS))
        assertEquals(30, Protocol.u8(c1, Protocol.COMMAND_ARGS + 1))
        val c2 = hex("A5 1D 7F 00")
        assertEquals(Protocol.TYPE_COMMAND, Protocol.validate(c2, c2.size, serverToClient = true))
        assertInvalid("A5 1D 01 02 07")
        assertInvalid("A5 1D 01 21" + " 00".repeat(33))
        assertInvalid("A5 1D 01")
        assertInvalid("A5 1D 01 02 07 1E", serverToClient = false)
    }

    @Test
    fun p8_p9_gameHints() {
        val rl = hex("A5 1B 10 72 6F 63 6B 65 74 6C 65 61 67 75 65 2E 65 78 65")
        assertEquals(Protocol.TYPE_GAME, Protocol.validate(rl, rl.size, serverToClient = true))
        assertEquals(16, Protocol.u8(rl, Protocol.GAME_NAME_LENGTH))
        assertEquals(GameModes.ROCKET, GameModes.forGame(rl, Protocol.GAME_NAME, 16))
        val none = hex("A5 1B 00")
        assertEquals(Protocol.TYPE_GAME, Protocol.validate(none, none.size, serverToClient = true))
        assertNull(GameModes.forGame(none, Protocol.GAME_NAME, 0))
        // §9.3 GAME rejections: N larger than what follows, N > 64, and GAME arriving at the server.
        assertInvalid("A5 1B 05 61 62")
        assertInvalid("A5 1B 41" + " 61".repeat(65))
        assertInvalid("A5 1B 00", serverToClient = false)
        // A longest legal name fits the receive buffers.
        val longest = hex("A5 1B 40" + " 61".repeat(64))
        assertEquals(Protocol.MAX_DATAGRAM, longest.size)
        assertEquals(Protocol.TYPE_GAME, Protocol.validate(longest, longest.size, serverToClient = true))
    }

    @Test
    fun everyListedGameMapsToItsMode() {
        for (mode in GameModes.ALL) {
            for (game in mode.games) {
                assertEquals(game, game.lowercase(), game)
                assertTrue(game, game.length <= Protocol.MAX_GAME_NAME_BYTES)
                val b = game.toByteArray(Charsets.US_ASCII)
                assertEquals(game, mode, GameModes.forGame(b, 0, b.size))
            }
        }
        val unknown = "notepad.exe".toByteArray(Charsets.US_ASCII)
        assertNull(GameModes.forGame(unknown, 0, unknown.size))
        // A prefix of a known name is not a match.
        val prefix = "rocketleague".toByteArray(Charsets.US_ASCII)
        assertNull(GameModes.forGame(prefix, 0, prefix.size))
    }

    @Test
    fun validateNeverReadsPastTheReportedLength() {
        // A reused receive buffer: stale bytes beyond `length` must not make a short datagram valid.
        val buffer = hex("A5 19 FF 40 00 01 34 12")
        assertEquals(Protocol.INVALID, Protocol.validate(buffer, 7, serverToClient = true))
        assertEquals(Protocol.INVALID, Protocol.validate(buffer, 9, serverToClient = true))
    }

    private fun assertInvalid(bytes: String, serverToClient: Boolean = true) {
        val b = hex(bytes)
        assertEquals("expected rejection of [$bytes]", Protocol.INVALID, Protocol.validate(b, b.size, serverToClient))
    }

    companion object {
        fun hex(s: String): ByteArray {
            val parts = s.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            return ByteArray(parts.size) { parts[it].toInt(16).toByte() }
        }
    }
}
