package com.mobilecontroller.client.protocol

/**
 * Wire protocol v1, implemented exactly as docs/PROTOCOL.md specifies.
 *
 * Pure Kotlin (no android.*) so the golden vectors run as JVM unit tests. Writers fill caller-owned
 * arrays and readers decode in place: the INPUT path never allocates.
 */
object Protocol {
    const val MAGIC = 0xA5
    const val VERSION = 1
    const val DEFAULT_PORT = 24680

    const val TYPE_INPUT = 0x1
    const val TYPE_PING = 0x2
    const val TYPE_BYE = 0x3
    const val TYPE_DISCOVER = 0x4
    const val TYPE_FEEDBACK = 0x9
    const val TYPE_PONG = 0xA
    const val TYPE_GAME = 0xB
    const val TYPE_DISCOVER_REPLY = 0xC

    /** Returned by [validate] for any datagram the receiver must silently drop. */
    const val INVALID = -1

    const val INPUT_SIZE = 16
    const val PING_SIZE = 8
    const val BYE_SIZE = 2
    const val DISCOVER_SIZE = 4
    const val FEEDBACK_SIZE = 8
    const val PONG_SIZE = 8
    const val DISCOVER_REPLY_HEADER = 7
    const val MAX_NAME_BYTES = 32
    const val GAME_HEADER = 3
    const val MAX_GAME_NAME_BYTES = 64

    /** Largest legal datagram (a GAME with a 64-byte name). */
    const val MAX_DATAGRAM = GAME_HEADER + MAX_GAME_NAME_BYTES

    // §3.1: XUSB_REPORT.wButtons bits.
    const val DPAD_UP = 0x0001
    const val DPAD_DOWN = 0x0002
    const val DPAD_LEFT = 0x0004
    const val DPAD_RIGHT = 0x0008
    const val START = 0x0010
    const val BACK = 0x0020
    const val LEFT_THUMB = 0x0040
    const val RIGHT_THUMB = 0x0080
    const val LEFT_SHOULDER = 0x0100
    const val RIGHT_SHOULDER = 0x0200
    const val GUIDE = 0x0400
    const val RESERVED = 0x0800
    const val A = 0x1000
    const val B = 0x2000
    const val X = 0x4000
    const val Y = 0x8000
    const val DPAD_MASK = DPAD_UP or DPAD_DOWN or DPAD_LEFT or DPAD_RIGHT

    /** Every defined button; the reserved bit is always 0 on the wire. */
    const val BUTTON_MASK = 0xF7FF

    // §5 FEEDBACK flags.
    const val FLAG_PAD_ATTACHED = 0x01
    const val FLAG_SERVER_FULL = 0x02
    const val FLAG_DRY_RUN = 0x04
    const val PLAYER_UNKNOWN = 0xFF

    // Field offsets of server -> client packets.
    const val FEEDBACK_LARGE_MOTOR = 2
    const val FEEDBACK_SMALL_MOTOR = 3
    const val FEEDBACK_PLAYER = 4
    const val FEEDBACK_FLAGS = 5
    const val FEEDBACK_LAST_SEQ = 6
    const val PONG_ID = 2
    const val PONG_CLIENT_TIME = 4
    const val REPLY_NONCE = 2
    const val REPLY_PADS_IN_USE = 4
    const val REPLY_MAX_PADS = 5
    const val REPLY_NAME_LENGTH = 6
    const val REPLY_NAME = 7
    const val GAME_NAME_LENGTH = 2
    const val GAME_NAME = 3

    fun verType(type: Int): Int = (VERSION shl 4) or type

    /** §3: a complete controller snapshot. Axes are clamped by the caller; the reserved bit is masked here. */
    fun writeInput(
        b: ByteArray, seq: Int, buttons: Int, leftTrigger: Int, rightTrigger: Int,
        lx: Int, ly: Int, rx: Int, ry: Int,
    ) {
        b[0] = MAGIC.toByte()
        b[1] = verType(TYPE_INPUT).toByte()
        put16(b, 2, seq)
        put16(b, 4, buttons and BUTTON_MASK)
        b[6] = leftTrigger.toByte()
        b[7] = rightTrigger.toByte()
        put16(b, 8, lx)
        put16(b, 10, ly)
        put16(b, 12, rx)
        put16(b, 14, ry)
    }

    /** §6: [clientTime] is opaque to the server and echoed back in the PONG. */
    fun writePing(b: ByteArray, pingId: Int, clientTime: Int) {
        b[0] = MAGIC.toByte()
        b[1] = verType(TYPE_PING).toByte()
        put16(b, 2, pingId)
        put32(b, 4, clientTime)
    }

    fun writeBye(b: ByteArray) {
        b[0] = MAGIC.toByte()
        b[1] = verType(TYPE_BYE).toByte()
    }

    fun writeDiscover(b: ByteArray, nonce: Int) {
        b[0] = MAGIC.toByte()
        b[1] = verType(TYPE_DISCOVER).toByte()
        put16(b, 2, nonce)
    }

    /**
     * §2 validation. Returns the packet type, or [INVALID] when the receiver must silently drop the
     * datagram: too short, bad magic, wrong version, unknown type, wrong direction or wrong length.
     * [serverToClient] selects which direction the receiver accepts (the phone passes true).
     */
    fun validate(b: ByteArray, length: Int, serverToClient: Boolean): Int {
        if (length < 2 || length > b.size || u8(b, 0) != MAGIC) return INVALID
        val vt = u8(b, 1)
        if (vt ushr 4 != VERSION) return INVALID
        val type = vt and 0x0F
        val size = when (type) {
            TYPE_INPUT -> INPUT_SIZE
            TYPE_PING -> PING_SIZE
            TYPE_BYE -> BYE_SIZE
            TYPE_DISCOVER -> DISCOVER_SIZE
            TYPE_FEEDBACK -> FEEDBACK_SIZE
            TYPE_PONG -> PONG_SIZE
            TYPE_DISCOVER_REPLY -> {
                if (length < DISCOVER_REPLY_HEADER) return INVALID
                val n = u8(b, REPLY_NAME_LENGTH)
                if (n > MAX_NAME_BYTES) return INVALID
                DISCOVER_REPLY_HEADER + n
            }
            TYPE_GAME -> {
                if (length < GAME_HEADER) return INVALID
                val n = u8(b, GAME_NAME_LENGTH)
                if (n > MAX_GAME_NAME_BYTES) return INVALID
                GAME_HEADER + n
            }
            else -> return INVALID
        }
        // Types 0x1-0x7 travel client -> server, 0x8-0xF server -> client.
        if ((type >= 0x8) != serverToClient) return INVALID
        return if (length == size) type else INVALID
    }

    /** INPUT buttons as the server must read them (§3.1: reserved bit masked off). */
    fun inputButtons(b: ByteArray): Int = u16(b, 4) and BUTTON_MASK

    /** Server name of a validated DISCOVER_REPLY. Allocates; discovery only. */
    fun replyName(b: ByteArray): String =
        String(b, REPLY_NAME, u8(b, REPLY_NAME_LENGTH), Charsets.UTF_8)

    fun u8(b: ByteArray, off: Int): Int = b[off].toInt() and 0xFF

    fun u16(b: ByteArray, off: Int): Int = u8(b, off) or (u8(b, off + 1) shl 8)

    fun i16(b: ByteArray, off: Int): Int = u16(b, off).toShort().toInt()

    fun u32(b: ByteArray, off: Int): Long = (u16(b, off).toLong()) or (u16(b, off + 2).toLong() shl 16)

    private fun put16(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte()
        b[off + 1] = (v ushr 8).toByte()
    }

    private fun put32(b: ByteArray, off: Int, v: Int) {
        put16(b, off, v)
        put16(b, off + 2, v ushr 16)
    }
}
