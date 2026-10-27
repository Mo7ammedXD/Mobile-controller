package com.mobilecontroller.client.ui

import com.mobilecontroller.client.GamepadState
import com.mobilecontroller.client.protocol.Protocol

/**
 * One on-screen control of a [GameMode]: what it does ([kind], [mask], [axis]), what it says ([label],
 * [sub]) and where it sits by default. Default placement is `x = ax * width + bx * u`,
 * `y = ay * height + by * u`, radius `r * u`, where u = min(height, width / 2.1) of the safe area, so a
 * layout anchored to a corner keeps its thumb distances on any phone or tablet.
 */
class ControlSpec(
    /** Stable id for logs, tests and saved layouts ("LS", "JUMP"). */
    val name: String,
    val kind: Int,
    /** Big text: the game action ("JUMP"). Sticks: the edge action's name, drawn on the edge ring. */
    val label: String,
    /** Small text: the Xbox input actually sent ("A", "RB+X"). */
    val sub: String,
    /**
     * Digital bits ([Protocol] buttons plus [GamepadState.LT] / [GamepadState.RT]). BUTTON: held while
     * touched. TOGGLE: held while switched on. STICK: held while the thumb is pushed past the edge ring.
     */
    val mask: Int,
    val color: Int,
    val ax: Float,
    val bx: Float,
    val ay: Float,
    val by: Float,
    val r: Float,
    /** Half-width / half-height in radii: 1 x 1 is a circle, anything else a rounded rectangle. */
    val aspectW: Float = 1f,
    val aspectH: Float = 1f,
    /** BUTTON: a finger slides between buttons that share a non-zero group (rolling the thumb). */
    val group: Int = 0,
    /** STICK: 0 = left axes, 1 = right axes. PEDAL: 0 = LT, 1 = RT. */
    val axis: Int = 0,
    /** TOGGLE: while any of these bits is held, the toggle's bits are withheld (reverse beats auto-gas). */
    val suppress: Int = 0,
) {
    companion object {
        const val STICK = 0
        const val DPAD = 1
        const val BUTTON = 2
        const val PEDAL = 3
        const val TOGGLE = 4
        const val MENU = 5
    }
}

/** A complete touch layout tuned for one style of game. See docs/GAME_MODES.md for the reasoning. */
class GameMode(
    val id: String,
    val title: String,
    /** Games it is designed for, shown in the picker. */
    val bestFor: String,
    /** One or two sentences on what makes this layout different. */
    val tips: String,
    val controls: Array<ControlSpec>,
    /** Motion control the mode offers: [MOTION_NONE], [MOTION_AIM] (gyro) or [MOTION_STEER] (tilt). */
    val motion: Int = MOTION_NONE,
    /** [MOTION_AIM]: with "only while aiming", gyro aims only while one of these bits is held. */
    val aimGate: Int = 0,
    /** Default stick feel: output floor past the game's deadzone (0..0.4), see StickMath.shape. */
    val antiDeadzone: Float = 0f,
    /** Default right-stick response exponent: 1 linear, > 1 finer near the centre (aiming). */
    val aimCurve: Float = 1f,
    /** Default stick sensitivity: full deflection after 1 / sensitivity of the travel. */
    val sensitivity: Float = 1f,
    /** Lower-case executable names (PROTOCOL §10 GAME) that select this mode automatically. */
    val games: Array<String> = emptyArray(),
) {
    fun indexOf(name: String): Int = controls.indexOfFirst { it.name == name }

    companion object {
        const val MOTION_NONE = 0

        /** Gyro: turning the phone aims the right stick. */
        const val MOTION_AIM = 1

        /** Tilt: turning the phone like a wheel steers the left stick's X axis. */
        const val MOTION_STEER = 2
    }
}

/**
 * The built-in modes. Labels assume each game's DEFAULT controller bindings; the small text on every
 * control shows the Xbox input it sends, so a player with custom bindings can still read the layout.
 *
 * Design rules (all modes): the thumbs rest in the lower-left and lower-right arcs, so the most used
 * actions are the largest controls there; anything a game wants HELD (gas, sprint) must not cost a
 * thumb, so it becomes a toggle or the stick's edge ring; actions a controller does with an index finger
 * (LB/RB/LT/RT) move into the thumb arcs or become one-touch combos; menus go to the top centre.
 */
object GameModes {
    private const val NEUTRAL = 0xFFB8C4D0.toInt()
    private const val GREEN = 0xFF5DC21E.toInt()
    private const val RED = 0xFFE5383B.toInt()
    private const val BLUE = 0xFF2D8CFF.toInt()
    private const val YELLOW = 0xFFF5C518.toInt()
    private const val ORANGE = 0xFFFF8A3D.toInt()

    private const val A = Protocol.A
    private const val B = Protocol.B
    private const val X = Protocol.X
    private const val Y = Protocol.Y
    private const val LB = Protocol.LEFT_SHOULDER
    private const val RB = Protocol.RIGHT_SHOULDER
    private const val LT = GamepadState.LT
    private const val RT = GamepadState.RT

    private const val FACE = 1

    /** Xbox colour of the single face button a control sends, so the screen matches in-game prompts. */
    private fun colorOf(mask: Int): Int = when (mask and (A or B or X or Y)) {
        A -> GREEN
        B -> RED
        X -> BLUE
        Y -> YELLOW
        else -> NEUTRAL
    }

    private fun stick(name: String, axis: Int, ax: Float, bx: Float, ay: Float, by: Float, r: Float, edgeMask: Int = 0, edgeLabel: String = "") =
        ControlSpec(name, ControlSpec.STICK, edgeLabel, "", edgeMask, NEUTRAL, ax, bx, ay, by, r, axis = axis)

    private fun dpad(ax: Float, bx: Float, ay: Float, by: Float, r: Float) =
        ControlSpec("DPAD", ControlSpec.DPAD, "", "", 0, NEUTRAL, ax, bx, ay, by, r)

    private fun button(
        name: String, label: String, sub: String, mask: Int,
        ax: Float, bx: Float, ay: Float, by: Float, r: Float,
        group: Int = 0, color: Int = colorOf(mask),
    ) = ControlSpec(name, ControlSpec.BUTTON, label, sub, mask, color, ax, bx, ay, by, r, group = group)

    /** Horizontal pill: shoulders, triggers and other top-edge controls. */
    private fun pill(name: String, label: String, sub: String, mask: Int, ax: Float, bx: Float, ay: Float, by: Float, r: Float) =
        ControlSpec(name, ControlSpec.BUTTON, label, sub, mask, NEUTRAL, ax, bx, ay, by, r, aspectW = 1.6f)

    /** Tall analog trigger: the higher the thumb on the pedal, the harder it presses. */
    private fun pedal(name: String, label: String, sub: String, axis: Int, ax: Float, bx: Float, ay: Float, by: Float, r: Float, color: Int) =
        ControlSpec(name, ControlSpec.PEDAL, label, sub, 0, color, ax, bx, ay, by, r, aspectW = 0.5f, axis = axis)

    private fun toggle(name: String, label: String, sub: String, mask: Int, suppress: Int, ax: Float, bx: Float, ay: Float, by: Float, r: Float) =
        ControlSpec(name, ControlSpec.TOGGLE, label, sub, mask, ORANGE, ax, bx, ay, by, r, aspectW = 1.9f, suppress = suppress)

    private fun menu() = ControlSpec("MENU", ControlSpec.MENU, "", "", 0, NEUTRAL, 0.5f, 0f, 0f, 0.115f, 0.04f)

    private fun back(label: String) = button("BACK", label, "BACK", Protocol.BACK, 0.5f, -0.20f, 0f, 0.25f, 0.055f)

    private fun start(label: String) = button("START", label, "START", Protocol.START, 0.5f, 0.20f, 0f, 0.25f, 0.055f)

    /**
     * Top-left D-pad for menus, quick chat and tactics: in reach of the left thumb, but above the left
     * stick's floating zone, so a thumb reaching for the top of the stick never sends a quick chat.
     */
    private fun cornerDpad() = dpad(0f, 0.13f, 0f, 0.13f, 0.08f)

    /** The physical Xbox pad, one control per input. Order matches the original saved-layout format. */
    val CLASSIC = GameMode(
        id = "classic",
        title = "Classic controller",
        bestFor = "Any game, menus, games not listed here",
        tips = "Every Xbox input in its usual place, labelled with the button name.",
        controls = arrayOf(
            stick("LS", 0, 0f, 0.30f, 1f, -0.42f, 0.17f),
            stick("RS", 1, 1f, -0.80f, 1f, -0.20f, 0.15f),
            dpad(0f, 0.74f, 1f, -0.17f, 0.14f),
            button("A", "A", "", A, 1f, -0.36f, 1f, -0.37f, 0.075f, FACE),
            button("B", "B", "", B, 1f, -0.21f, 1f, -0.52f, 0.075f, FACE),
            button("X", "X", "", X, 1f, -0.51f, 1f, -0.52f, 0.075f, FACE),
            button("Y", "Y", "", Y, 1f, -0.36f, 1f, -0.67f, 0.075f, FACE),
            pill("LB", "LB", "", LB, 0f, 0.50f, 0f, 0.10f, 0.075f),
            pill("RB", "RB", "", RB, 1f, -0.50f, 0f, 0.10f, 0.075f),
            pill("LT", "LT", "", LT, 0f, 0.20f, 0f, 0.10f, 0.075f),
            pill("RT", "RT", "", RT, 1f, -0.20f, 0f, 0.10f, 0.075f),
            button("BACK", "BACK", "", Protocol.BACK, 0.5f, -0.22f, 0f, 0.25f, 0.055f),
            button("GUIDE", "GUIDE", "", Protocol.GUIDE, 0.5f, 0f, 0f, 0.25f, 0.065f),
            button("START", "START", "", Protocol.START, 0.5f, 0.22f, 0f, 0.25f, 0.055f),
            button("L3", "L3", "", Protocol.LEFT_THUMB, 0f, 0.66f, 1f, -0.62f, 0.06f),
            button("R3", "R3", "", Protocol.RIGHT_THUMB, 1f, -1.16f, 1f, -0.40f, 0.06f),
            menu(),
        ),
        motion = GameMode.MOTION_AIM,
        aimGate = LT,
    )

    /**
     * Rocket League: throttle is held ~90 % of a match, so it is a toggle (reverse overrides it), which
     * frees the right thumb for the three actions that decide plays: boost, jump, powerslide/air roll.
     * Boost and jump are the largest controls; J+B between them presses both, so rolling the thumb
     * BOOST -> J+B -> JUMP never drops boost (aerials, fast aerials).
     */
    val ROCKET = GameMode(
        id = "rocket",
        title = "Car soccer (Rocket League)",
        bestFor = "Rocket League",
        tips = "Tap AUTO GAS at kick-off: you drive without holding a trigger, REV overrides it. " +
            "Roll your thumb from BOOST over J+B onto JUMP for aerials.",
        controls = arrayOf(
            stick("STEER", 0, 0f, 0.33f, 1f, -0.40f, 0.19f),
            button("BOOST", "BOOST", "B", B, 1f, -0.19f, 1f, -0.30f, 0.125f, FACE),
            button("JUMP", "JUMP", "A", A, 1f, -0.53f, 1f, -0.17f, 0.125f, FACE),
            button("JB", "J+B", "A+B", A or B, 1f, -0.36f, 1f, -0.235f, 0.05f, FACE, ORANGE),
            button("SLIDE", "SLIDE", "X · air roll", X, 1f, -0.62f, 1f, -0.45f, 0.095f, FACE),
            button("CAM", "BALL CAM", "Y", Y, 1f, -0.24f, 1f, -0.60f, 0.075f),
            button("REV", "REV", "LT", LT, 1f, -0.88f, 1f, -0.17f, 0.085f),
            stick("LOOK", 1, 1f, -1.16f, 1f, -0.42f, 0.09f),
            toggle("GAS", "AUTO GAS", "RT", RT, LT, 1f, -0.22f, 0f, 0.10f, 0.06f),
            cornerDpad(),
            back("SCORE"),
            start("MENU"),
            menu(),
        ),
        antiDeadzone = 0.10f,
        sensitivity = 1.1f,
        games = arrayOf("rocketleague.exe"),
    )

    /**
     * EA SPORTS FC / FIFA: sprint is the stick's edge ring (push past the ring to sprint, like pushing a
     * real stick into its rim while holding RT), so the right thumb only has to pass and shoot. Pass is
     * the largest control in the resting spot, shoot sits towards the corner; finesse (RB+X) and chip
     * (LB+X) shots are one-touch combos instead of two-finger chords.
     */
    val FOOTBALL = GameMode(
        id = "football",
        title = "Football (EA SPORTS FC / FIFA)",
        bestFor = "EA SPORTS FC, FIFA (default \"Classic\" controls)",
        tips = "Push the left stick past its ring to sprint. Defending: PASS = contain, LOB = tackle, " +
            "SHOOT = slide tackle, SWITCH changes player, JOCKEY is LT.",
        controls = arrayOf(
            stick("MOVE", 0, 0f, 0.32f, 1f, -0.40f, 0.19f, RT, "SPRINT"),
            button("PASS", "PASS", "A", A, 1f, -0.33f, 1f, -0.20f, 0.115f, FACE),
            button("SHOOT", "SHOOT", "X", X, 1f, -0.13f, 1f, -0.42f, 0.10f, FACE),
            button("THROUGH", "THROUGH", "Y", Y, 1f, -0.36f, 1f, -0.50f, 0.085f, FACE),
            button("LOB", "LOB", "B · cross", B, 1f, -0.58f, 1f, -0.17f, 0.085f, FACE),
            button("FINESSE", "FINESSE", "RB+X", RB or X, 1f, -0.13f, 1f, -0.66f, 0.07f, FACE),
            button("CHIP", "CHIP", "LB+X", LB or X, 1f, -0.33f, 1f, -0.76f, 0.065f, FACE),
            // Defence: SWITCH is tapped constantly, so it gets the closest free spot; SPRINT is mostly the ring.
            button("SWITCH", "SWITCH", "LB", LB, 1f, -0.62f, 1f, -0.42f, 0.075f),
            button("JOCKEY", "JOCKEY", "LT", LT, 1f, -0.84f, 1f, -0.18f, 0.075f),
            button("SPRINT", "SPRINT", "RT", RT, 1f, -0.86f, 1f, -0.42f, 0.075f),
            stick("SKILL", 1, 1f, -1.18f, 1f, -0.30f, 0.10f),
            cornerDpad(),
            start("PAUSE"),
            menu(),
        ),
        antiDeadzone = 0.10f,
        games = arrayOf("fc26.exe", "fc25.exe", "fc24.exe", "fifa23.exe", "fifa22.exe", "fifa21.exe"),
    )

    /**
     * Racing: steering is the only thing the left thumb does; the right thumb rocks between two analog
     * pedals like a foot (higher on the pedal = harder), with the handbrake next to them.
     */
    val RACING = GameMode(
        id = "racing",
        title = "Racing (Forza, F1, Need for Speed…)",
        bestFor = "Forza Horizon / Motorsport, F1, Need for Speed, The Crew, Assetto Corsa, Dirt",
        tips = "Pedals are analog: touch high on a pedal for full power, low for a gentle press, and " +
            "slide to modulate.",
        controls = arrayOf(
            stick("STEER", 0, 0f, 0.32f, 1f, -0.40f, 0.19f),
            pedal("GAS", "GAS", "RT", 1, 1f, -0.16f, 1f, -0.27f, 0.21f, GREEN),
            pedal("BRAKE", "BRAKE", "LT", 0, 1f, -0.41f, 1f, -0.23f, 0.17f, RED),
            button("EBRAKE", "E-BRAKE", "A", A, 1f, -0.64f, 1f, -0.20f, 0.085f),
            button("B", "B", "", B, 1f, -0.16f, 1f, -0.64f, 0.065f),
            button("Y", "Y", "", Y, 1f, -0.40f, 1f, -0.64f, 0.065f),
            button("X", "X", "", X, 1f, -0.64f, 1f, -0.46f, 0.065f),
            pill("LB", "LB", "", LB, 1f, -0.52f, 0f, 0.10f, 0.06f),
            pill("RB", "RB", "", RB, 1f, -0.20f, 0f, 0.10f, 0.06f),
            stick("LOOK", 1, 1f, -1.05f, 1f, -0.30f, 0.09f),
            cornerDpad(),
            back("VIEW"),
            start("MENU"),
            menu(),
        ),
        motion = GameMode.MOTION_STEER,
        antiDeadzone = 0.12f,
        games = arrayOf(
            "forzahorizon5.exe", "forzahorizon4.exe", "forzamotorsport.exe", "f1_25.exe", "f1_24.exe", "f1_23.exe",
            "nfsunbound.exe", "nfsheat.exe", "thecrewmotorfest.exe", "dirtrally2.exe", "wrc.exe",
        ),
    )

    /**
     * Shooters: the right thumb aims, so firing must not need it. FIRE exists twice (right of the aim
     * zone and above the move stick for the left thumb, like mobile shooters), sprint is the move
     * stick's edge ring (L3) and aim-down-sights sits just above the right FIRE.
     */
    val SHOOTER = GameMode(
        id = "shooter",
        title = "Shooters (CoD, Fortnite, Apex…)",
        bestFor = "Call of Duty, Fortnite, Apex Legends, Battlefield, Halo, Destiny, third-person action",
        tips = "Aim with the right stick and fire with the LEFT thumb (FIRE above the move stick). " +
            "Push the move stick past its ring to sprint.",
        controls = arrayOf(
            stick("MOVE", 0, 0f, 0.30f, 1f, -0.40f, 0.17f, Protocol.LEFT_THUMB, "SPRINT"),
            stick("AIM", 1, 1f, -0.86f, 1f, -0.40f, 0.15f),
            button("FIRE", "FIRE", "RT", RT, 1f, -0.22f, 1f, -0.40f, 0.12f, color = RED),
            button("FIRE_L", "FIRE", "RT", RT, 0f, 0.72f, 1f, -0.68f, 0.08f, color = RED),
            button("ADS", "AIM", "LT", LT, 1f, -0.17f, 1f, -0.68f, 0.085f),
            // A column between the aim zone and FIRE the right thumb rolls along: reload, melee, jump.
            button("RELOAD", "RELOAD", "X", X, 1f, -0.43f, 1f, -0.64f, 0.075f),
            button("MELEE", "MELEE", "R3", Protocol.RIGHT_THUMB, 1f, -0.43f, 1f, -0.40f, 0.065f),
            button("JUMP", "JUMP", "A", A, 1f, -0.45f, 1f, -0.14f, 0.085f),
            button("CROUCH", "CROUCH", "B", B, 1f, -0.13f, 1f, -0.14f, 0.075f),
            button("SWAP", "SWAP", "Y", Y, 1f, -0.70f, 1f, -0.86f, 0.065f),
            pill("LB", "ABILITY", "LB", LB, 1f, -0.48f, 0f, 0.10f, 0.06f),
            pill("RB", "GRENADE", "RB", RB, 1f, -0.20f, 0f, 0.10f, 0.06f),
            cornerDpad(),
            back("MAP"),
            start("MENU"),
            menu(),
        ),
        motion = GameMode.MOTION_AIM,
        aimGate = LT,
        antiDeadzone = 0.15f,
        aimCurve = 1.6f,
        sensitivity = 1.2f,
        games = arrayOf(
            "cod.exe", "fortniteclient-win64-shipping.exe", "r5apex.exe", "r5apex_dx12.exe", "bf2042.exe",
            "haloinfinite.exe", "destiny2.exe", "overwatch.exe",
        ),
    )

    /**
     * Fighting games and platformers: a big D-pad for precise directions and the 2 x 3 arcade grid
     * (X Y RB over A B RT - Street Fighter's classic punch/kick layout, Tekken's 1 2 / 3 4 on the first
     * two columns). Fingers slide across the grid for plinks and rolling inputs.
     */
    val ARCADE = GameMode(
        id = "arcade",
        title = "Fighting & platformers",
        bestFor = "Street Fighter, Tekken, Mortal Kombat, Guilty Gear, Hollow Knight, Cuphead, Celeste, emulators",
        tips = "Arcade grid: top row punches (LP MP HP), bottom row kicks (LK MK HK). Two fingers on the " +
            "grid for throws and supers; slide across it for rolling inputs.",
        controls = arrayOf(
            dpad(0f, 0.30f, 1f, -0.38f, 0.20f),
            button("X", "X", "LP", X, 1f, -0.78f, 1f, -0.40f, 0.095f, FACE),
            button("Y", "Y", "MP", Y, 1f, -0.50f, 1f, -0.42f, 0.095f, FACE),
            button("RB", "RB", "HP", RB, 1f, -0.22f, 1f, -0.40f, 0.095f, FACE),
            button("A", "A", "LK", A, 1f, -0.78f, 1f, -0.16f, 0.095f, FACE),
            button("B", "B", "MK", B, 1f, -0.50f, 1f, -0.18f, 0.095f, FACE),
            button("RT", "RT", "HK", RT, 1f, -0.22f, 1f, -0.16f, 0.095f, FACE),
            button("LB", "LB", "3P", LB, 1f, -1.04f, 1f, -0.40f, 0.07f, FACE),
            button("LT", "LT", "3K", LT, 1f, -1.04f, 1f, -0.16f, 0.07f, FACE),
            back("BACK"),
            start("START"),
            menu(),
        ),
        games = arrayOf(
            "streetfighter6.exe", "polaris-win64-shipping.exe", "mk12.exe", "ggst-win64-shipping.exe",
            "hollow_knight.exe", "cuphead.exe", "celeste.exe", "retroarch.exe",
        ),
    )

    val ALL = arrayOf(CLASSIC, ROCKET, FOOTBALL, RACING, SHOOTER, ARCADE)

    fun byId(id: String?): GameMode = ALL.firstOrNull { it.id == id } ?: CLASSIC

    // Every known executable name as ASCII bytes, with its mode: matched against GAME packets on the
    // receiver thread without allocating.
    private val gameNames: Array<ByteArray>
    private val gameModes: Array<GameMode>

    init {
        val names = ArrayList<ByteArray>()
        val modes = ArrayList<GameMode>()
        for (m in ALL) {
            for (g in m.games) {
                names += g.toByteArray(Charsets.US_ASCII)
                modes += m
            }
        }
        gameNames = names.toTypedArray()
        gameModes = modes.toTypedArray()
    }

    /** The mode for the game whose executable name is `b[off until off + n]` (lower-case ASCII), or null. */
    fun forGame(b: ByteArray, off: Int, n: Int): GameMode? {
        for (i in gameNames.indices) {
            val name = gameNames[i]
            if (name.size != n) continue
            var k = 0
            while (k < n && name[k] == b[off + k]) k++
            if (k == n) return gameModes[i]
        }
        return null
    }
}
