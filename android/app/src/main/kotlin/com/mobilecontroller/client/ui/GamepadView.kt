package com.mobilecontroller.client.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import com.mobilecontroller.client.LOG_TAG
import com.mobilecontroller.client.R
import com.mobilecontroller.client.input.TouchRouter
import com.mobilecontroller.client.net.UdpLink
import com.mobilecontroller.client.protocol.Protocol
import com.mobilecontroller.client.ui.ControlLayout.Companion.MAX_CONTROLS
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The whole controller surface: multi-touch in, Canvas out.
 *
 * Touch path (allocation-free): MotionEvent -> [TouchRouter] (primitive arrays) -> GamepadState ->
 * [UdpLink.wake] (LockSupport.unpark). The packet leaves on the sender thread before this view even
 * redraws. Every Paint, rect and label (and its measured width) is prepared when a mode is applied, so
 * onDraw never allocates either.
 */
@SuppressLint("ViewConstructor") // Created in code only; it needs the link it drives.
class GamepadView(context: Context, private val link: UdpLink) : View(context) {
    interface Listener {
        fun onMenu()

        /** The user saved the layout in edit mode: [serialized] is null for the (adaptive) defaults. */
        fun onLayoutSaved(serialized: String?)

        /** The link became connected or lost the PC (drives screen dimming). */
        fun onConnectionChanged(connected: Boolean)

        fun onEditingChanged(editing: Boolean)
    }

    val layout = ControlLayout()
    val router = TouchRouter(layout, link.state)
    var listener: Listener? = null
    var haptics = true

    /** Shown in the status line instead of the link state while Android 17's permission is missing. */
    var permissionMissing = false
        set(value) {
            if (field != value) {
                field = value
                updateStatus()
            }
        }

    var editing = false
        private set

    private val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val density = resources.displayMetrics.density

    // Safe area: the display cutout insets (bars are hidden, so their space is usable).
    private var insetLeft = 0
    private var insetTop = 0
    private var insetRight = 0
    private var insetBottom = 0
    private val placed = RectF()

    private var savedLayout: String? = null

    // Per-control text and colour of the current mode, with label widths measured at textSize 100.
    private val labels = Array(MAX_CONTROLS) { "" }
    private val subs = Array(MAX_CONTROLS) { "" }
    private val labelWidth = FloatArray(MAX_CONTROLS)
    private val subWidth = FloatArray(MAX_CONTROLS)
    private val colors = IntArray(MAX_CONTROLS)

    // Edit mode state.
    private var selected = -1
    private var dragPointer = -1
    private var pinchPointer = -1
    private var dragDx = 0f
    private var dragDy = 0f
    private var pinchStartDistance = 0f
    private var pinchStartSize = 0f
    private var toolbarDown = -1
    private val toolLeft = FloatArray(TOOL_COUNT)
    private val toolRight = FloatArray(TOOL_COUNT)
    private var toolTop = 0f
    private var toolBottom = 0f
    private val toolLabels = arrayOf(
        context.getString(R.string.edit_smaller), context.getString(R.string.edit_bigger),
        context.getString(R.string.edit_undo), context.getString(R.string.edit_snap),
        context.getString(R.string.edit_reset), context.getString(R.string.edit_cancel),
        context.getString(R.string.edit_save),
    )

    /** Edit mode: snap dragged controls to a grid, and the layouts to step back through. */
    private var snap = true
    private val undo = ArrayList<String>()

    /** The comfortable thumb arcs, shown while editing (see docs/GAME_MODES.md). */
    private val thumbZones = Path().apply { fillType = Path.FillType.EVEN_ODD }

    // Player colour: a frame in this phone's player colour, and a big "P1" when the PC assigns it.
    private var linkUp = false
    private var bannerPlayer = -1
    private var bannerStart = 0L
    private val bannerTick = object : Runnable {
        override fun run() {
            invalidate()
            if (SystemClock.uptimeMillis() - bannerStart < BANNER_MS) postOnAnimation(this)
        }
    }

    // Status line, rebuilt at most 4 times per second without allocating.
    private val status = CharLine(STATUS_CAPACITY)
    private var statusCode = -1
    private var statusPlayer = -1
    private var statusFlags = -1
    private var statusRtt = Int.MIN_VALUE
    private var statusColor = COLOR_TEXT
    private val textConnected = context.getString(R.string.status_connected)
    private val textDryRun = context.getString(R.string.status_dry_run)
    private val textWaiting = context.getString(R.string.status_waiting)
    private val textNotListening = context.getString(R.string.status_not_listening)
    private val textServerFull = context.getString(R.string.status_server_full)
    private val textPermission = context.getString(R.string.status_permission)
    private val textBlocked = context.getString(R.string.status_blocked)
    private val textNoRoute = context.getString(R.string.status_no_route)
    private val textUnknownHost = context.getString(R.string.status_unknown_host)
    private val textNoTarget = context.getString(R.string.status_no_target)
    private val textEditing = context.getString(R.string.status_editing)
    private val statusTick = object : Runnable {
        override fun run() {
            updateStatus()
            postDelayed(this, STATUS_PERIOD_MS)
        }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringDash = DashPathEffect(floatArrayOf(10f * density, 8f * density), 0f)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val rect = RectF()

    init {
        // performClick() opens the settings; it must not also play a click sound mid-game.
        isSoundEffectsEnabled = false
        contentDescription = context.getString(R.string.surface_description)
        applyMode(GameModes.CLASSIC, null)
    }

    // ---------------------------------------------------------------------------------------------
    // Touch

    @SuppressLint("ClickableViewAccessibility") // performClick() is called on a gear tap.
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked
        // Deliver this gesture's samples as they arrive instead of batched to the next vsync (up to a
        // frame less latency). API 30+ also gets the persistent request in onAttachedToWindow.
        if (action == MotionEvent.ACTION_DOWN) requestUnbufferedDispatch(e)
        if (editing) {
            onEditTouch(e)
            return true
        }
        var flags = 0
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                flags = router.down(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) flags = flags or router.move(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> flags = router.up(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> flags = router.cancelAll()
        }
        // Wake the sender first: the haptic and redraw below must not delay the packet.
        if ((flags and TouchRouter.STATE) != 0) link.wake()
        if (haptics) haptic(flags)
        if ((flags and TouchRouter.VISUAL) != 0) invalidate()
        if ((flags and TouchRouter.MENU_TAP) != 0) performClick()
        return true
    }

    /**
     * Haptic landmarks, so the player knows what the thumb is on without looking: a firm confirm for
     * sprint starting or a toggle switching on, a double "reject" for empty glass or a toggle off, a
     * tick for a button, and a light clock tick when a stick reaches full output.
     */
    private fun haptic(flags: Int) {
        val modern = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val effect = when {
            (flags and (TouchRouter.EDGE or TouchRouter.TOGGLE_ON)) != 0 ->
                if (modern) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
            (flags and (TouchRouter.MISS or TouchRouter.TOGGLE_OFF)) != 0 ->
                if (modern) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.VIRTUAL_KEY
            (flags and TouchRouter.PRESS) != 0 -> HapticFeedbackConstants.VIRTUAL_KEY
            (flags and TouchRouter.RIM) != 0 -> HapticFeedbackConstants.CLOCK_TICK
            else -> return
        }
        performHapticFeedback(effect)
    }

    /** The gear (or an accessibility click on the surface) opens the settings. */
    override fun performClick(): Boolean {
        super.performClick()
        listener?.onMenu()
        return true
    }

    /** Lifts every finger and switches toggles off: pause, dialogs, layout changes. */
    fun releaseAll() {
        if ((router.releaseAll() and TouchRouter.STATE) != 0) link.wake()
        dragPointer = -1
        pinchPointer = -1
        toolbarDown = -1
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_POINTER)
    }

    // ---------------------------------------------------------------------------------------------
    // Mode and layout

    /** Switches to [mode], with its saved layout string (null or unreadable -> the mode's defaults). */
    fun applyMode(mode: GameMode, serialized: String?) {
        releaseAll()
        editing = false
        layout.load(mode)
        router.reset()
        link.wake()
        for (c in 0 until layout.count) {
            val s = layout.spec(c)
            labels[c] = s.label
            subs[c] = s.sub
            colors[c] = s.color
            text.textSize = MEASURE_SIZE
            labelWidth[c] = if (s.label.isEmpty()) 0f else text.measureText(s.label)
            subWidth[c] = if (s.sub.isEmpty()) 0f else text.measureText(s.sub)
        }
        savedLayout = serialized
        if (serialized == null || !layout.parse(serialized)) layout.resetToDefaults()
        onGeometryChanged()
    }

    /** Left-handed: mirror every control left <-> right. */
    fun setMirrored(mirror: Boolean) {
        if (mirror == layout.mirrored) return
        releaseAll()
        layout.setMirrored(mirror)
        onGeometryChanged()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val c = insets.getInsets(WindowInsets.Type.displayCutout())
            setInsets(c.left, c.top, c.right, c.bottom)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val c = insets.displayCutout
            setInsets(c?.safeInsetLeft ?: 0, c?.safeInsetTop ?: 0, c?.safeInsetRight ?: 0, c?.safeInsetBottom ?: 0)
        }
        return super.onApplyWindowInsets(insets)
    }

    private fun setInsets(left: Int, top: Int, right: Int, bottom: Int) {
        if (left == insetLeft && top == insetTop && right == insetRight && bottom == insetBottom) return
        insetLeft = left
        insetTop = top
        insetRight = right
        insetBottom = bottom
        placeControls()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        placeControls()
    }

    private fun placeControls() {
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        val margin = min(w, h) * EDGE_MARGIN
        val l = insetLeft + margin
        val t = insetTop + margin
        val r = w - insetRight - margin
        val b = h - insetBottom - margin
        if (placed.left == l && placed.top == t && placed.right == r && placed.bottom == b) return
        placed.set(l, t, r, b)
        // Geometry under the fingers is about to move: release rather than reinterpret held touches.
        releaseAll()
        layout.place(l, t, r, b)
        onGeometryChanged()
    }

    private fun onGeometryChanged() {
        if (layout.unit <= 0f) return
        placeToolbar()
        // Thumb arcs: a band 0.22u-0.62u from each bottom corner of the safe area, as in the docs' pictures.
        val u = min(layout.height, layout.width / 2.1f)
        thumbZones.reset()
        for (cx in floatArrayOf(layout.left, layout.right)) {
            thumbZones.addCircle(cx, layout.bottom, 0.62f * u, Path.Direction.CW)
            thumbZones.addCircle(cx, layout.bottom, 0.22f * u, Path.Direction.CW)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Keep edge swipes (back gesture) from stealing a thumb that lands near the screen edge on a
            // stick zone. The system caps the excluded height per edge; allocation here is setup-only.
            val rects = ArrayList<Rect>(2)
            for (c in 0 until layout.count) {
                if (!layout.isStick(c)) continue
                val zone = layout.r[c] * ControlLayout.STICK_ZONE
                rects += Rect(
                    (layout.x[c] - zone).toInt().coerceAtLeast(0), (layout.y[c] - zone).toInt().coerceAtLeast(0),
                    (layout.x[c] + zone).toInt().coerceAtMost(width), (layout.y[c] + zone).toInt().coerceAtMost(height),
                )
            }
            systemGestureExclusionRects = rects
        }
        statusCode = -1
        updateStatus()
        invalidate()
        // After this layout pass completes, so the on-screen origin is final.
        if (debuggable) post(::logGeometry)
    }

    private fun logGeometry() {
        val origin = IntArray(2)
        getLocationOnScreen(origin)
        Log.i(
            LOG_TAG,
            "geom view=${width}x$height origin=${origin[0]},${origin[1]} " +
                "safe=${placed.left.toInt()},${placed.top.toInt()},${placed.right.toInt()},${placed.bottom.toInt()} " +
                "unit=${layout.unit.toInt()} custom=${layout.customized} mode=${layout.mode.id}",
        )
        for (c in 0 until layout.count) Log.i(LOG_TAG, layout.describe(c, origin[0], origin[1]))
    }

    // ---------------------------------------------------------------------------------------------
    // Edit mode

    fun startEditing() {
        releaseAll()
        editing = true
        selected = -1
        undo.clear()
        placeToolbar()
        statusCode = -1
        updateStatus()
        invalidate()
        listener?.onEditingChanged(true)
    }

    /** Leaves edit mode, discarding unsaved changes. */
    fun cancelEditing() {
        if (!editing) return
        editing = false
        undo.clear()
        if (savedLayout == null || !layout.parse(savedLayout)) layout.resetToDefaults()
        onGeometryChanged()
        listener?.onEditingChanged(false)
    }

    /** Remembers the current layout for Undo ("" stands for the adaptive defaults). Edit mode only. */
    private fun pushUndo() {
        val current = if (layout.customized) layout.serialize() else ""
        if (undo.lastOrNull() != current) undo += current
        if (undo.size > MAX_UNDO) undo.removeAt(0)
    }

    private fun popUndo() {
        val previous = undo.removeLastOrNull() ?: return
        if (previous.isEmpty() || !layout.parse(previous)) layout.resetToDefaults()
        selected = -1
    }

    /** Edit mode: positions snap to a grid of [SNAP_GRID] units so rows and columns line up. */
    private fun snapped(v: Float, origin: Float): Float {
        if (!snap) return v
        val grid = SNAP_GRID * layout.unit
        return origin + Math.round((v - origin) / grid) * grid
    }

    private fun finishEditing(save: Boolean) {
        if (save) {
            // Untouched defaults stay adaptive (recomputed for any screen) instead of being frozen.
            savedLayout = if (layout.customized) layout.serialize() else null
            listener?.onLayoutSaved(savedLayout)
        }
        cancelEditing()
    }

    private fun onEditTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                toolbarDown = hitToolbar(e.x, e.y)
                if (toolbarDown < 0) selected = layout.hitAny(e.x, e.y)
                if (toolbarDown < 0 && selected >= 0) {
                    pushUndo()
                    dragPointer = e.getPointerId(0)
                    dragDx = layout.x[selected] - e.x
                    dragDy = layout.y[selected] - e.y
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (selected >= 0 && dragPointer >= 0 && pinchPointer < 0) {
                pinchPointer = e.getPointerId(e.actionIndex)
                pinchStartDistance = pinchDistance(e)
                pinchStartSize = layout.size[selected]
            }
            MotionEvent.ACTION_MOVE -> {
                if (selected < 0) return
                val i = e.findPointerIndex(dragPointer)
                if (i < 0) return
                if (pinchPointer >= 0) {
                    val d = pinchDistance(e)
                    if (pinchStartDistance > 0f && d > 0f) {
                        layout.resize(selected, pinchStartSize * d / pinchStartDistance / layout.size[selected])
                    }
                } else {
                    layout.moveTo(selected, snapped(e.getX(i) + dragDx, layout.left), snapped(e.getY(i) + dragDy, layout.top))
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (id == dragPointer) dragPointer = -1
                if (id == pinchPointer || dragPointer < 0) pinchPointer = -1
                // Re-anchor the drag on the finger that remains so the control does not jump.
                val i = e.findPointerIndex(dragPointer)
                if (selected >= 0 && i >= 0) {
                    dragDx = layout.x[selected] - e.getX(i)
                    dragDy = layout.y[selected] - e.getY(i)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val tool = toolbarDown
                toolbarDown = -1
                dragPointer = -1
                pinchPointer = -1
                if (e.actionMasked == MotionEvent.ACTION_UP && tool >= 0 && hitToolbar(e.x, e.y) == tool) {
                    onTool(tool)
                    if (!editing) return
                }
                // A tap that moved nothing leaves no undo step behind.
                if (tool < 0 && undo.isNotEmpty() && undo.last() == (if (layout.customized) layout.serialize() else "")) {
                    undo.removeAt(undo.size - 1)
                }
                placeToolbar()
            }
        }
        invalidate()
    }

    private fun onTool(tool: Int) {
        when (tool) {
            TOOL_SMALLER -> if (selected >= 0) {
                pushUndo()
                layout.resize(selected, 1f / RESIZE_STEP)
            }
            TOOL_BIGGER -> if (selected >= 0) {
                pushUndo()
                layout.resize(selected, RESIZE_STEP)
            }
            TOOL_UNDO -> popUndo()
            TOOL_SNAP -> snap = !snap
            TOOL_RESET -> {
                pushUndo()
                layout.resetToDefaults()
                selected = -1
            }
            TOOL_CANCEL -> finishEditing(save = false)
            TOOL_SAVE -> finishEditing(save = true)
        }
    }

    private fun pinchDistance(e: MotionEvent): Float {
        val a = e.findPointerIndex(dragPointer)
        val b = e.findPointerIndex(pinchPointer)
        if (a < 0 || b < 0) return 0f
        return hypot(e.getX(a) - e.getX(b), e.getY(a) - e.getY(b))
    }

    /**
     * A centred row of tool buttons, at the height (among a few candidates) that covers the fewest
     * controls, so no control can end up hidden underneath it. Re-evaluated whenever a finger lifts.
     */
    private fun placeToolbar() {
        val u = layout.unit
        if (u <= 0f) return
        val w = TOOL_WIDTH * u
        val gap = TOOL_GAP * u
        val h = TOOL_HEIGHT * u
        var x = (layout.left + layout.right) / 2 - (TOOL_COUNT * w + (TOOL_COUNT - 1) * gap) / 2
        for (k in 0 until TOOL_COUNT) {
            toolLeft[k] = x
            toolRight[k] = x + w
            x += w + gap
        }
        var bestTop = 0f
        var bestScore = Float.MAX_VALUE
        for (k in 0..TOOL_CANDIDATES) {
            val cy = layout.top + layout.height * (0.2f + 0.6f * k / TOOL_CANDIDATES)
            var overlaps = 0
            for (c in 0 until layout.count) {
                val ex = if (layout.isStick(c)) layout.r[c] else layout.halfW[c]
                if (layout.x[c] + ex < toolLeft[0] || layout.x[c] - ex > toolRight[TOOL_COUNT - 1]) continue
                if (abs(layout.y[c] - cy) < layout.halfH[c] + h / 2) overlaps++
            }
            // Fewest overlaps first, then closest to the vertical centre.
            val score = overlaps * 10f + abs(cy - (layout.top + layout.bottom) / 2) / layout.height
            if (score < bestScore) {
                bestScore = score
                bestTop = cy - h / 2
            }
        }
        toolTop = bestTop
        toolBottom = bestTop + h
    }

    private fun hitToolbar(px: Float, py: Float): Int {
        if (py < toolTop || py > toolBottom) return -1
        for (k in 0 until TOOL_COUNT) if (px >= toolLeft[k] && px <= toolRight[k]) return k
        return -1
    }

    // ---------------------------------------------------------------------------------------------
    // Status line

    fun startStatusUpdates() {
        removeCallbacks(statusTick)
        post(statusTick)
    }

    fun stopStatusUpdates() {
        removeCallbacks(statusTick)
    }

    private fun updateStatus() {
        val code = when {
            editing -> STATUS_EDITING
            permissionMissing -> STATUS_PERMISSION
            else -> link.status(System.nanoTime())
        }
        // The real link state, also while the status line shows something else (editing).
        val linked = link.status(System.nanoTime()) == UdpLink.STATUS_CONNECTED
        if (linked != linkUp) {
            linkUp = linked
            listener?.onConnectionChanged(linked)
        }
        val connected = code == UdpLink.STATUS_CONNECTED
        val player = if (connected) link.playerIndex else -1
        val flags = if (connected) link.feedbackFlags else -1
        val rtt = if (connected) link.rttMicros.let { if (it < 0) -1 else (it + 500) / 1000 } else -1
        if (player in 0..3 && player != bannerPlayer && (flags and Protocol.FLAG_PAD_ATTACHED) != 0) {
            // The PC just made this phone player N: say so, big, in the player's colour.
            bannerPlayer = player
            bannerStart = SystemClock.uptimeMillis()
            removeCallbacks(bannerTick)
            postOnAnimation(bannerTick)
        } else if (!linked) {
            bannerPlayer = -1
        }
        if (code == statusCode && player == statusPlayer && flags == statusFlags && rtt == statusRtt) return
        if (code != statusCode && code != STATUS_EDITING) {
            Log.i(LOG_TAG, "status ${statusName(code)} target=${link.host}:${link.port}")
        }
        statusCode = code
        statusPlayer = player
        statusFlags = flags
        statusRtt = rtt
        val s = status.clear()
        statusColor = COLOR_WARN
        when (code) {
            UdpLink.STATUS_CONNECTED -> {
                statusColor = COLOR_OK
                s.append(textConnected)
                if ((flags and Protocol.FLAG_DRY_RUN) != 0) {
                    s.append(SEPARATOR).append(textDryRun)
                } else if ((flags and Protocol.FLAG_PAD_ATTACHED) != 0 && player in 0..3) {
                    s.append(SEPARATOR).append('P').append(player + 1)
                }
                if (rtt >= 0) s.append(SEPARATOR).append(rtt).append(" ms")
            }
            UdpLink.STATUS_WAITING -> s.append(textWaiting).append(' ').append(link.host).append(':').append(link.port)
            UdpLink.STATUS_NOT_LISTENING -> {
                statusColor = COLOR_ERROR
                s.append(textNotListening).append(SEPARATOR).append(link.host).append(':').append(link.port)
            }
            UdpLink.STATUS_SERVER_FULL -> {
                statusColor = COLOR_ERROR
                s.append(textServerFull)
            }
            UdpLink.STATUS_BLOCKED -> {
                statusColor = COLOR_ERROR
                s.append(textBlocked)
            }
            UdpLink.STATUS_NO_ROUTE -> s.append(textNoRoute)
            UdpLink.STATUS_UNKNOWN_HOST -> {
                statusColor = COLOR_ERROR
                s.append(textUnknownHost).append(SEPARATOR).append(link.host)
            }
            STATUS_PERMISSION -> {
                statusColor = COLOR_ERROR
                s.append(textPermission)
            }
            STATUS_EDITING -> {
                statusColor = COLOR_TEXT
                s.append(textEditing)
            }
            else -> s.append(textNoTarget)
        }
        invalidate()
    }

    private fun statusName(code: Int): String = when (code) {
        STATUS_PERMISSION -> "PERMISSION_NEEDED"
        else -> UdpLink.statusName(code)
    }

    // ---------------------------------------------------------------------------------------------
    // Drawing (no allocation: every object used here is a field)

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(COLOR_BACKGROUND)
        if (layout.unit <= 0f) return
        if (editing) {
            fill.color = COLOR_THUMB_ZONE
            canvas.drawPath(thumbZones, fill)
        }
        drawPlayer(canvas)
        for (c in 0 until layout.count) {
            when (layout.kind[c]) {
                ControlSpec.STICK -> drawStick(canvas, c)
                ControlSpec.DPAD -> drawDpad(canvas, c)
                ControlSpec.MENU -> drawGear(canvas, c)
                ControlSpec.PEDAL -> drawPedal(canvas, c)
                ControlSpec.TOGGLE -> drawToggle(canvas, c)
                else -> drawButton(canvas, c)
            }
        }
        if (editing) drawEditOverlay(canvas)
        drawStatus(canvas)
    }

    /**
     * A frame in this phone's player colour (four phones on one sofa can tell which is which at a
     * glance), and for [BANNER_MS] after the PC assigns a player, a big fading "P1".
     */
    private fun drawPlayer(canvas: Canvas) {
        val p = statusPlayer
        if (!linkUp || p !in 0..3) return
        val color = PLAYER_COLORS[p]
        val u = layout.unit
        stroke.color = (color and 0x00FFFFFF) or 0x90000000.toInt()
        stroke.strokeWidth = max(u * 0.008f, density)
        val inset = stroke.strokeWidth
        rect.set(inset / 2, inset / 2, width - inset / 2, height - inset / 2)
        canvas.drawRoundRect(rect, u * 0.04f, u * 0.04f, stroke)
        val elapsed = SystemClock.uptimeMillis() - bannerStart
        if (bannerPlayer == p && elapsed < BANNER_MS) {
            val alpha = ((1f - elapsed.toFloat() / BANNER_MS) * 255).toInt().coerceIn(0, 255)
            drawText(canvas, PLAYER_LABELS[p], (layout.left + layout.right) / 2, layout.top + layout.height * 0.45f, u * 0.3f,
                (color and 0x00FFFFFF) or (alpha shl 24))
        }
    }

    private fun drawStick(canvas: Canvas, c: Int) {
        val held = router.isHeld(c)
        val r = layout.r[c]
        val bx = if (held) router.stickX[c] else layout.x[c]
        val by = if (held) router.stickY[c] else layout.y[c]
        if (layout.mask[c] != 0) {
            // Edge ring: push the thumb out to it for the stick's edge action (sprint).
            val ring = r * TouchRouter.EDGE_ON
            val on = router.edgeOn[c]
            dashed.color = if (on) COLOR_EDGE else COLOR_RING
            dashed.strokeWidth = max(r * (if (on) 0.05f else 0.025f), density)
            dashed.pathEffect = if (on) null else ringDash
            canvas.drawCircle(bx, by, ring, dashed)
            drawText(canvas, labels[c], bx, by - ring - r * 0.08f, r * 0.17f, if (on) COLOR_EDGE else COLOR_TEXT)
        }
        fill.color = COLOR_FILL
        canvas.drawCircle(bx, by, r, fill)
        stroke.color = if (held) COLOR_ACTIVE else COLOR_RING
        stroke.strokeWidth = r * 0.04f
        canvas.drawCircle(bx, by, r, stroke)
        fill.color = if (held) COLOR_ACTIVE else COLOR_KNOB
        canvas.drawCircle(if (held) router.knobX[c] else bx, if (held) router.knobY[c] else by, r * KNOB, fill)
    }

    private fun drawDpad(canvas: Canvas, c: Int) {
        val x = layout.x[c]
        val y = layout.y[c]
        val r = layout.r[c]
        val a = r * 0.34f
        val bits = router.dpadBits[c]
        val corner = a * 0.35f
        drawArm(canvas, c, x - a, y - r, x + a, y - a, corner, (bits and Protocol.DPAD_UP) != 0)
        drawArm(canvas, c, x - a, y + a, x + a, y + r, corner, (bits and Protocol.DPAD_DOWN) != 0)
        drawArm(canvas, c, x - r, y - a, x - a, y + a, corner, (bits and Protocol.DPAD_LEFT) != 0)
        drawArm(canvas, c, x + a, y - a, x + r, y + a, corner, (bits and Protocol.DPAD_RIGHT) != 0)
        fill.color = COLOR_FILL
        rect.set(x - a, y - a, x + a, y + a)
        canvas.drawRect(rect, fill)
    }

    private fun drawArm(canvas: Canvas, c: Int, l: Float, t: Float, r: Float, b: Float, corner: Float, pressed: Boolean) {
        rect.set(l, t, r, b)
        fill.color = if (pressed) COLOR_ACTIVE else COLOR_FILL
        canvas.drawRoundRect(rect, corner, corner, fill)
        stroke.color = COLOR_RING
        stroke.strokeWidth = layout.r[c] * 0.025f
        canvas.drawRoundRect(rect, corner, corner, stroke)
    }

    private fun drawButton(canvas: Canvas, c: Int) {
        val x = layout.x[c]
        val y = layout.y[c]
        val hw = layout.halfW[c]
        val hh = layout.halfH[c]
        val pressed = router.isHeld(c)
        val color = colors[c]
        fill.color = if (pressed) color else tint(color)
        stroke.color = color
        stroke.strokeWidth = max(min(hw, hh) * 0.07f, density)
        if (layout.isRound(c)) {
            canvas.drawCircle(x, y, hw, fill)
            canvas.drawCircle(x, y, hw, stroke)
        } else {
            rect.set(x - hw, y - hh, x + hw, y + hh)
            canvas.drawRoundRect(rect, min(hw, hh), min(hw, hh), fill)
            canvas.drawRoundRect(rect, min(hw, hh), min(hw, hh), stroke)
        }
        drawLabels(canvas, c, x, y, hw, hh, if (pressed) COLOR_BACKGROUND else color)
    }

    /** Analog pedal: fills from the bottom with the pressure being sent. */
    private fun drawPedal(canvas: Canvas, c: Int) {
        val x = layout.x[c]
        val y = layout.y[c]
        val hw = layout.halfW[c]
        val hh = layout.halfH[c]
        val color = colors[c]
        val corner = hw * 0.5f
        rect.set(x - hw, y - hh, x + hw, y + hh)
        fill.color = tint(color)
        canvas.drawRoundRect(rect, corner, corner, fill)
        val level = router.pedalValue[c] / 255f
        if (level > 0f) {
            rect.set(x - hw, y + hh - 2 * hh * level, x + hw, y + hh)
            fill.color = color
            canvas.drawRoundRect(rect, corner, corner, fill)
            rect.set(x - hw, y - hh, x + hw, y + hh)
        }
        stroke.color = color
        stroke.strokeWidth = max(hw * 0.07f, density)
        canvas.drawRoundRect(rect, corner, corner, stroke)
        drawLabels(canvas, c, x, y - hh * 0.6f, hw, hw, if (level > 0.75f) COLOR_BACKGROUND else color)
    }

    /** Toggle chip: lit while on (e.g. AUTO GAS holding the throttle). */
    private fun drawToggle(canvas: Canvas, c: Int) {
        val x = layout.x[c]
        val y = layout.y[c]
        val hw = layout.halfW[c]
        val hh = layout.halfH[c]
        val on = router.toggleOn[c]
        val color = colors[c]
        rect.set(x - hw, y - hh, x + hw, y + hh)
        fill.color = if (on) color else tint(color)
        canvas.drawRoundRect(rect, hh, hh, fill)
        stroke.color = color
        stroke.strokeWidth = max(hh * 0.08f, density)
        canvas.drawRoundRect(rect, hh, hh, stroke)
        drawLabels(canvas, c, x, y, hw, hh, if (on) COLOR_BACKGROUND else color)
    }

    private fun drawGear(canvas: Canvas, c: Int) {
        val x = layout.x[c]
        val y = layout.y[c]
        val r = layout.r[c]
        fill.color = if (router.isHeld(c)) COLOR_ACTIVE else COLOR_FILL
        canvas.drawCircle(x, y, r, fill)
        fill.color = COLOR_TEXT
        val tooth = r * 0.12f
        canvas.save()
        for (k in 0 until 8) {
            canvas.rotate(45f, x, y)
            rect.set(x - tooth, y - r * 0.72f, x + tooth, y - r * 0.4f)
            canvas.drawRect(rect, fill)
        }
        canvas.restore()
        stroke.color = COLOR_TEXT
        stroke.strokeWidth = r * 0.17f
        canvas.drawCircle(x, y, r * 0.38f, stroke)
    }

    /**
     * The action name, shrunk to fit the control, with the Xbox input it sends in small text below.
     * Widths were measured once per mode, so fitting is arithmetic only.
     */
    private fun drawLabels(canvas: Canvas, c: Int, x: Float, y: Float, hw: Float, hh: Float, color: Int) {
        val label = labels[c]
        if (label.isEmpty()) return
        val m = min(hw, hh)
        val maxWidth = hw * LABEL_FILL
        val hasSub = subs[c].isNotEmpty()
        var size = if (label.length <= 2) m * (if (hasSub) 0.8f else 0.95f) else m * 0.55f
        size = min(size, maxWidth * MEASURE_SIZE / labelWidth[c])
        drawText(canvas, label, x, if (hasSub) y - hh * 0.14f else y, size, color)
        if (hasSub) {
            val subSize = min(m * 0.3f, maxWidth * MEASURE_SIZE / subWidth[c])
            drawText(canvas, subs[c], x, y + hh * 0.5f, subSize, (color and 0x00FFFFFF) or 0xB0000000.toInt())
        }
    }

    private fun drawText(canvas: Canvas, s: String, x: Float, y: Float, size: Float, color: Int) {
        if (s.isEmpty()) return
        text.textSize = size
        text.color = color
        canvas.drawText(s, x, y - (text.descent() + text.ascent()) / 2, text)
    }

    private fun drawEditOverlay(canvas: Canvas) {
        val u = layout.unit
        // Stick zones: where a thumb may land to grab the floating stick.
        stroke.color = COLOR_RING
        stroke.strokeWidth = density
        for (c in 0 until layout.count) {
            if (!layout.isStick(c)) continue
            val zone = layout.r[c] * ControlLayout.STICK_ZONE
            rect.set(layout.x[c] - zone, layout.y[c] - zone, layout.x[c] + zone, layout.y[c] + zone)
            canvas.drawRoundRect(rect, zone * 0.2f, zone * 0.2f, stroke)
        }
        val c = selected
        if (c >= 0) {
            stroke.color = COLOR_SELECTED
            stroke.strokeWidth = 3 * density
            val ex = layout.halfW[c] + 4 * density
            val ey = layout.halfH[c] + 4 * density
            rect.set(layout.x[c] - ex, layout.y[c] - ey, layout.x[c] + ex, layout.y[c] + ey)
            canvas.drawRoundRect(rect, ey * 0.3f, ey * 0.3f, stroke)
        }
        val radius = (toolBottom - toolTop) / 2
        for (k in 0 until TOOL_COUNT) {
            rect.set(toolLeft[k], toolTop, toolRight[k], toolBottom)
            fill.color = when {
                k == toolbarDown -> COLOR_SELECTED
                k == TOOL_SAVE -> COLOR_SAVE
                k == TOOL_SNAP && snap -> COLOR_SNAP_ON
                k == TOOL_UNDO && undo.isEmpty() -> COLOR_TOOL_DISABLED
                else -> COLOR_TOOL
            }
            canvas.drawRoundRect(rect, radius, radius, fill)
            drawText(canvas, toolLabels[k], rect.centerX(), rect.centerY(), u * 0.045f, COLOR_TEXT_STRONG)
        }
    }

    private fun drawStatus(canvas: Canvas) {
        val n = status.length
        if (n == 0) return
        val u = layout.unit
        text.textSize = u * 0.036f
        val w = text.measureText(status.chars, 0, n)
        val cx = (layout.left + layout.right) / 2
        val cy = layout.top + u * 0.035f
        val hh = u * 0.03f
        rect.set(cx - w / 2 - hh, cy - hh, cx + w / 2 + hh, cy + hh)
        fill.color = COLOR_STATUS_BACKGROUND
        canvas.drawRoundRect(rect, hh, hh, fill)
        text.color = statusColor
        canvas.drawText(status.chars, 0, n, cx, cy - (text.descent() + text.ascent()) / 2, text)
    }

    companion object {
        /** Status codes beyond [UdpLink]'s. */
        const val STATUS_PERMISSION = 100
        const val STATUS_EDITING = 101

        private const val STATUS_PERIOD_MS = 250L
        private const val STATUS_CAPACITY = 96
        private const val SEPARATOR = " · "

        /** Gap kept between the controls and the safe-area edge, as a fraction of the shorter side. */
        private const val EDGE_MARGIN = 0.015f
        private const val KNOB = 0.45f
        private const val RESIZE_STEP = 1.1f

        /** Text size at which label widths are measured; drawing scales from it. */
        private const val MEASURE_SIZE = 100f

        /** A label may use this many half-widths of its control (a little less than the full width). */
        private const val LABEL_FILL = 1.5f

        private const val TOOL_SMALLER = 0
        private const val TOOL_BIGGER = 1
        private const val TOOL_UNDO = 2
        private const val TOOL_SNAP = 3
        private const val TOOL_RESET = 4
        private const val TOOL_CANCEL = 5
        private const val TOOL_SAVE = 6
        private const val TOOL_COUNT = 7
        private const val TOOL_CANDIDATES = 6
        private const val TOOL_WIDTH = 0.18f
        private const val TOOL_GAP = 0.025f
        private const val TOOL_HEIGHT = 0.1f
        private const val MAX_UNDO = 30

        /** Edit-mode grid, in units (2 % of the shorter side). */
        private const val SNAP_GRID = 0.02f

        private const val BANNER_MS = 2500L

        // Player 1-4: green, blue, orange, purple - distinct for colour-blind players too (hue + brightness).
        private val PLAYER_COLORS = intArrayOf(0xFF5DC21E.toInt(), 0xFF2D8CFF.toInt(), 0xFFFF8A3D.toInt(), 0xFFB36BFF.toInt())
        private val PLAYER_LABELS = arrayOf("P1", "P2", "P3", "P4")

        private const val COLOR_BACKGROUND = 0xFF0D1117.toInt()
        private const val COLOR_FILL = 0xFF1C232D.toInt()
        private const val COLOR_RING = 0xFF3B4757.toInt()
        private const val COLOR_KNOB = 0xFF4A5868.toInt()
        private const val COLOR_ACTIVE = 0xFFDCE6F0.toInt()
        private const val COLOR_TEXT = 0xFFB8C4D0.toInt()
        private const val COLOR_TEXT_STRONG = 0xFFFFFFFF.toInt()
        private const val COLOR_SELECTED = 0xFF4FA3FF.toInt()
        private const val COLOR_TOOL = 0xE02A3442.toInt()
        private const val COLOR_TOOL_DISABLED = 0x802A3442.toInt()
        private const val COLOR_SNAP_ON = 0xE02F5D8A.toInt()
        private const val COLOR_THUMB_ZONE = 0x302ECC71
        private const val COLOR_SAVE = 0xE0237A3B.toInt()
        private const val COLOR_STATUS_BACKGROUND = 0xB0000000.toInt()
        private const val COLOR_OK = 0xFF6FD08C.toInt()
        private const val COLOR_WARN = 0xFFF2C14E.toInt()
        private const val COLOR_ERROR = 0xFFFF6B6B.toInt()
        private const val COLOR_EDGE = 0xFFFF8A3D.toInt()

        /** The control colour at low opacity: the resting look of every button. */
        private fun tint(color: Int): Int = (color and 0x00FFFFFF) or 0x38000000
    }
}
