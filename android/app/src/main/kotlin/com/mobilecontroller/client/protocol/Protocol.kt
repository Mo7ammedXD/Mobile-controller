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
    const val TYPE_STATUS = 0x5
    const val TYPE_FEEDBACK = 0x9
    const val TYPE_PONG = 0xA
    const val TYPE_GAME = 0xB
    const val TYPE_DISCOVER_REPLY = 0xC
    const val TYPE_COMMAND = 0xD

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
    const val STATUS_HEADER = 21
    const val MAX_STATUS_FIELD_BYTES = 32
    const val MAX_STATUS_SIZE = STATUS_HEADER + 3 * MAX_STATUS_FIELD_BYTES
    const val COMMAND_HEADER = 4
    const val MAX_COMMAND_ARG_BYTES = 32

    /** Largest legal datagram the client receives (a GAME with a 64-byte name). */
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
    const val FLAG_TARGET_DS4 = 0x08
    const val FLAG_AWAITING_APPROVAL = 0x10
    const val FLAG_DENIED = 0x20
    const val FLAG_STATUS_ACK = 0x40
    const val PLAYER_UNKNOWN = 0xFF

    // §12 STATUS.
    const val STATUS_CHARGING = 0x01
    const val STATUS_MENU_OPEN = 0x02
    const val STATUS_MOTION_ACTIVE = 0x04
    const val BATTERY_UNKNOWN = 0xFF
    const val RTT_UNKNOWN = 0xFFFF
    const val OUTPUT_NONE = 0
    const val OUTPUT_XBOX360 = 1
    const val OUTPUT_DS4 = 2

    // §13 COMMAND ops and offsets.
    const val COMMAND_IDENTIFY = 0x01
    const val COMMAND_OP = 2
    const val COMMAND_ARG_LENGTH = 3
    const val COMMAND_ARGS = 4

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
     * §12 STATUS into [b] (at least [MAX_STATUS_SIZE] bytes); returns its length. The names are the first
     * [deviceNameLength] / [profileNameLength] bytes of already encoded arrays ([encodeName]). [rttTenths] is in
     * 0.1 ms, saturating; [battery] 0..100 or [BATTERY_UNKNOWN]. Allocation-free.
     */
    fun writeStatus(
        b: ByteArray, deviceId: Long, battery: Int, flags: Int, rttTenths: Int, rateHz: Int, output: Int, appVersion: Int,
        deviceName: ByteArray, deviceNameLength: Int, profileName: ByteArray, profileNameLength: Int,
    ): Int {
        b[0] = MAGIC.toByte()
        b[1] = verType(TYPE_STATUS).toByte()
        put32(b, 2, deviceId.toInt())
        put32(b, 6, (deviceId ushr 32).toInt())
        b[10] = battery.toByte()
        b[11] = flags.toByte()
        put16(b, 12, rttTenths.coerceIn(0, RTT_UNKNOWN))
        b[14] = rateHz.coerceIn(0, 255).toByte()
        b[15] = output.toByte()
        put16(b, 16, appVersion.coerceIn(0, 0xFFFF))
        b[18] = 0
        b[19] = deviceNameLength.toByte()
        b[20] = profileNameLength.toByte()
        System.arraycopy(deviceName, 0, b, STATUS_HEADER, deviceNameLength)
        System.arraycopy(profileName, 0, b, STATUS_HEADER + deviceNameLength, profileNameLength)
        return STATUS_HEADER + deviceNameLength + profileNameLength
    }

    /**
     * UTF-8 of [name] cut to at most [MAX_STATUS_FIELD_BYTES] bytes without splitting a character (the §8 rule).
     * Allocates; called when a name changes, never per packet.
     */
    fun encodeName(name: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(MAX_STATUS_FIELD_BYTES)
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
            if (out.size() + bytes.size > MAX_STATUS_FIELD_BYTES) break
            out.write(bytes)
            i += Character.charCount(cp)
        }
        return out.toByteArray()
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
            TYPE_STATUS -> {
                if (length < STATUS_HEADER) return INVALID
                val e = u8(b, 18)
                val d = u8(b, 19)
                val p = u8(b, 20)
                if (e > MAX_STATUS_FIELD_BYTES || d > MAX_STATUS_FIELD_BYTES || p > MAX_STATUS_FIELD_BYTES) return INVALID
                STATUS_HEADER + e + d + p
            }
            TYPE_COMMAND -> {
                if (length < COMMAND_HEADER) return INVALID
                val n = u8(b, COMMAND_ARG_LENGTH)
                if (n > MAX_COMMAND_ARG_BYTES) return INVALID
                COMMAND_HEADER + n
            }
            else -> return INVALID
        }
        // Types 0x1-0x7 travel client -> server, 0x8-0xF server -> client.
        if ((type >= 0x8) != serverToClient) return INVALID
        if (length != size) return INVALID
        // §12: a STATUS without a device id is malformed.
        if (type == TYPE_STATUS && u32(b, 2) == 0L && u32(b, 6) == 0L) return INVALID
        return type
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
