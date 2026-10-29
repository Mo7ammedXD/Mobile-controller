package com.mobilecontroller.client

import com.mobilecontroller.client.input.GyroFilter
import com.mobilecontroller.client.input.MotionMath
import com.mobilecontroller.client.input.StickMath
import com.mobilecontroller.client.input.TouchRouter
import com.mobilecontroller.client.protocol.Protocol
import com.mobilecontroller.client.ui.CharLine
import com.mobilecontroller.client.ui.ControlLayout
import com.mobilecontroller.client.ui.GameMode
import com.mobilecontroller.client.ui.GameModes
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import kotlin.math.cos
import kotlin.math.sin

/**
 * The steady-state input path must not allocate, or GC pauses would turn into input jitter.
 * Measures the JVM's per-thread allocation counter around a million touch -> state -> packet cycles
 * that exercise every control type of the game modes.
 */
class ZeroAllocationTest {
    private val threadBean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private val state = GamepadState()
    private val football = Mode(GameModes.FOOTBALL)
    private val rocket = Mode(GameModes.ROCKET)
    private val racing = Mode(GameModes.RACING)
    private val packet = ByteArray(Protocol.INPUT_SIZE)
    private val statusPacket = ByteArray(Protocol.MAX_STATUS_SIZE)
    private val deviceName = Protocol.encodeName("Pixel 8")
    private val profileName = Protocol.encodeName("Football")
    private val status = CharLine(96)
    private var sink = 0

    private inner class Mode(mode: GameMode) {
        val layout = ControlLayout().apply { load(mode); place(0f, 0f, 2400f, 1080f) }
        val router = TouchRouter(layout, state)

        // Control ids resolved once, as the view does: name lookups are setup-time only.
        fun id(name: String) = layout.indexOf(name).also { check(it >= 0) { name } }
    }

    private val gyro = GyroFilter().apply { accelGain = 1.5f; tightening = 0.05f }
    private var sensorTime = 0L

    init {
        // Exercise the shaped sticks, every stick option, the minimum press and the floating cluster too.
        state.minPressNanos = 20_000_000L
        football.router.antiDeadzone[0] = 0.15f
        football.router.sensitivity[0] = 1.3f
        football.router.curve[1] = 1.6f
        football.router.outerDeadzone[0] = 0.1f
        football.router.anchor[0] = TouchRouter.ANCHOR_FOLLOW
        // A smaller edge ring lets the 1.4 r circle below drag the follow stick's centre too.
        football.router.edgeOnRadius = 1.2f
        football.router.deadzoneShape[1] = StickMath.DZ_SNAP
        rocket.router.floating = true
        rocket.router.dpadFourWay = true
        racing.router.pedalCurve = 1.4f
        racing.router.deadzoneShape[0] = StickMath.DZ_AXIAL
    }

    private val move = football.id("MOVE")
    private val shoot = football.id("SHOOT")
    private val finesse = football.id("FINESSE")
    private val boost = rocket.id("BOOST")
    private val jb = rocket.id("JB")
    private val jump = rocket.id("JUMP")
    private val gas = rocket.id("GAS")
    private val pedal = racing.id("GAS")
    private val dpad = rocket.id("DPAD")

    /**
     * One frame of play: a thumb circling past the sprint ring, a shot and a finesse combo, a boost ->
     * jump roll, the auto-gas toggle, a pedal squeeze and a D-pad roll, then a send.
     */
    private fun cycle(i: Int) {
        val f = football.layout
        val r = f.r[move]
        val a = (i % 360) * 0.017453292f
        if (i % 360 == 0) {
            football.router.up(0)
            football.router.down(0, f.x[move], f.y[move])
        }
        sink += football.router.move(0, f.x[move] + 1.4f * r * cos(a), f.y[move] + 1.4f * r * sin(a))
        when (i % 9) {
            0 -> sink += football.router.down(1, f.x[shoot], f.y[shoot])
            2 -> sink += football.router.down(2, f.x[finesse], f.y[finesse])
            4 -> sink += football.router.up(2)
            6 -> sink += football.router.up(1)
        }
        val k = rocket.layout
        when (i % 13) {
            0 -> sink += rocket.router.down(3, k.x[boost], k.y[boost])
            3 -> sink += rocket.router.move(3, k.x[jb], k.y[jb])
            6 -> sink += rocket.router.move(3, k.x[jump], k.y[jump])
            8 -> sink += rocket.router.up(3)
            10 -> sink += rocket.router.down(4, k.x[gas], k.y[gas]) or rocket.router.up(4)
            11 -> sink += rocket.router.down(5, k.x[dpad], k.y[dpad] - k.r[dpad]) or rocket.router.up(5)
        }
        val g = racing.layout
        when (i % 17) {
            0 -> sink += racing.router.down(6, g.x[pedal], g.y[pedal] + g.halfH[pedal])
            in 1..10 -> sink += racing.router.move(6, g.x[pedal], g.y[pedal] + g.halfH[pedal] * (1f - 0.2f * (i % 17)))
            12 -> sink += racing.router.up(6)
        }
        // Sensor thread: gyro aim and tilt steering samples (200 Hz on a phone) into the motion offsets.
        val t = i * 0.005f
        sensorTime += 5_000_000L
        if (i % 1000 == 0) gyro.startCalibration(50_000_000L)
        val aim = gyro.process(0.4f * sin(t), 0.2f * cos(t), 0.1f, 6.9f, 0f, 6.9f, MotionMath.ROTATION_90, sensorTime)
        val steer = MotionMath.steer(8f, 4f * sin(t), 3f, MotionMath.ROTATION_90, 1f, 0.1f)
        state.setMotion(steer, StickMath.unpackX(aim), StickMath.unpackY(aim))
        if (state.dirty || i % 2 == 0) state.snapshot(packet)
        sink += (state.deadlineNanos and 1L).toInt()
        sink += packet[2].toInt()
        if (i % 120 == 0) {
            // Sender thread: the once-per-second STATUS (§12).
            sink += Protocol.writeStatus(
                statusPacket, 0x1234L, i % 101, Protocol.STATUS_CHARGING, i % 500, 120, Protocol.OUTPUT_NONE, 3,
                deviceName, deviceName.size, profileName, profileName.size,
            )
        }
        if (i % 30 == 0) {
            // The status line rebuild (UI thread, 4 Hz) shares the no-allocation rule.
            status.clear().append("Connected").append(" · P").append(1 + (i and 3)).append(" · ").append(i % 50).append(" ms")
            sink += status.length
        }
    }

    @Test
    fun touchToPacketAllocatesNothing() {
        assumeTrue(threadBean.isThreadAllocatedMemorySupported)
        threadBean.isThreadAllocatedMemoryEnabled = true
        // Warm-up: class initialisation and JIT compilation allocate; steady state must not.
        for (i in 0 until 200_000) cycle(i)
        val calibrationStart = threadBean.currentThreadAllocatedBytes
        val calibration = threadBean.currentThreadAllocatedBytes - calibrationStart

        val iterations = 1_000_000
        val before = threadBean.currentThreadAllocatedBytes
        for (i in 0 until iterations) cycle(i)
        val allocated = threadBean.currentThreadAllocatedBytes - before - calibration
        println("ZeroAllocationTest: $iterations cycles allocated $allocated bytes (counter overhead $calibration), sink=$sink")
        assertEquals("bytes allocated by $iterations touch -> packet cycles", 0L, allocated)
    }
}
