package com.example.fingerprintmouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.FingerprintGestureController
import android.accessibilityservice.FingerprintGestureController.FingerprintGestureCallback
import android.accessibilityservice.GestureDescription
import android.animation.ObjectAnimator
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Turns the phone's capacitive fingerprint sensor into a laptop-style trackpad.
 *
 * Pipeline:
 *   1. FingerprintGestureController reports one of 4 raw directional swipes.
 *   2. We nudge an in-memory (cursorX, cursorY) coordinate and redraw a small
 *      overlay icon there via WindowManager, so the user has a visible pointer.
 *   3. A specific gesture pattern (see [handleGesture]) triggers a synthetic
 *      tap at the pointer's current coordinates via dispatchGesture().
 *
 * IMPORTANT PLATFORM CONSTRAINT — read before changing the "click" logic:
 * The public FingerprintGestureController API only ever reports FOUR gesture
 * constants: SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT. There is no "tap"
 * or "press" event exposed for the fingerprint sensor — the OS does not give
 * accessibility services raw touch data from the sensor, only these four
 * pre-classified swipes. So a literal "double-tap to click" as described in
 * the spec isn't something the platform can report.
 *
 * The practical stand-in implemented here: swiping the SAME direction twice
 * within [DOUBLE_SWIPE_WINDOW_MS] is treated as a "click" instead of a second
 * move, since a deliberate double-flick is very unlikely to happen by accident
 * during normal single-step cursor movement. This is a design choice, not a
 * platform API — see [handleGesture] to tune or replace it (e.g. you could
 * instead reserve one specific direction, like DOWN, purely as the click
 * trigger and use only UP/LEFT/RIGHT for movement).
 */
class FingerprintMouseService : AccessibilityService() {

    companion object {
        private const val TAG = "FingerprintMouseSvc"

        // How far the cursor moves per single swipe, in dp (density-independent).
        private const val MOVE_STEP_DP = 48f

        // Visual size of the cursor icon, in dp.
        private const val CURSOR_SIZE_DP = 40f

        // Two same-direction swipes inside this window = a "click", not two moves.
        private const val DOUBLE_SWIPE_WINDOW_MS = 350L

        // Duration of the synthetic tap stroke dispatched to the system.
        // A real fingertip tap is brief; a very short stroke reads as a tap
        // rather than a long-press to whatever app receives it.
        private const val CLICK_STROKE_DURATION_MS = 40L
    }

    private lateinit var windowManager: WindowManager
    private lateinit var cursorView: ImageView
    private lateinit var cursorParams: WindowManager.LayoutParams

    // Small always-on-screen readout so you can tell, just by looking at the
    // phone, whether the sensor is reporting anything at all — no adb/logcat
    // needed. Remove this overlay once gestures are confirmed working end to end.
    private lateinit var debugView: TextView
    private lateinit var debugParams: WindowManager.LayoutParams
    private var detectionAvailable: Boolean? = null // null = not checked yet
    private var receivedCount = 0
    private var lastReceivedName = "none yet"

    private var screenWidthPx = 0
    private var screenHeightPx = 0
    private var cursorSizePx = 0
    private var moveStepPx = 0

    // The cursor's logical position, in raw screen pixels, (0,0) = top-left.
    // This is the single source of truth; the overlay's LayoutParams are
    // just cursorX/cursorY re-expressed with a half-icon offset (see
    // updateCursorPosition) so the icon's *center* sits on the coordinate,
    // not its top-left corner.
    private var cursorX = 0
    private var cursorY = 0

    private var lastGestureType = -1
    private var lastGestureAtMs = 0L

    // Named distinctly from the inherited `fingerprintGestureController`
    // property (from AccessibilityService.getFingerprintGestureController())
    // to avoid shadowing it — we cache its value here once the service connects.
    private var gestureController: FingerprintGestureController? = null

    private val fingerprintCallback = object : FingerprintGestureCallback() {
        override fun onGestureDetected(gesture: Int) {
            handleGesture(gesture)
        }

        override fun onGestureDetectionAvailabilityChanged(available: Boolean) {
            // This flips to false, for example, while the fingerprint sensor
            // is busy elsewhere (e.g. the lock screen is asking for auth).
            Log.i(TAG, "Fingerprint gesture detection available: $available")
            detectionAvailable = available
            updateDebugOverlay()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val metrics = resources.displayMetrics
        screenWidthPx = metrics.widthPixels
        screenHeightPx = metrics.heightPixels
        cursorSizePx = dpToPx(CURSOR_SIZE_DP)
        moveStepPx = dpToPx(MOVE_STEP_DP)

        // Start the pointer in the middle of the screen.
        cursorX = screenWidthPx / 2
        cursorY = screenHeightPx / 2

        addCursorOverlay()
        addDebugOverlay()
        registerFingerprintGestures()
    }

    /** Registers for swipe callbacks, guarded by a hardware/state availability check. */
    private fun registerFingerprintGestures() {
        // `fingerprintGestureController` here is the inherited property from
        // AccessibilityService — only valid to read after onServiceConnected().
        val controller = fingerprintGestureController
        gestureController = controller

        if (controller == null) {
            Log.w(TAG, "No FingerprintGestureController on this device.")
            return
        }

        if (!controller.isGestureDetectionAvailable) {
            // Common causes: no fingerprint hardware, no enrolled fingerprints,
            // or the sensor is currently reserved for a system auth prompt.
            // We still register below — onGestureDetectionAvailabilityChanged
            // will fire if/when it becomes available.
            Log.w(TAG, "Fingerprint gesture detection is not available right now.")
        }

        detectionAvailable = controller.isGestureDetectionAvailable
        updateDebugOverlay()

        controller.registerFingerprintGestureCallback(fingerprintCallback, null)
    }

    /**
     * Adds the small pointer graphic as a system overlay.
     *
     * TYPE_ACCESSIBILITY_OVERLAY is the key detail: it's a window type reserved
     * for bound AccessibilityServices, so it does NOT require the user to grant
     * the separate "draw over other apps" (SYSTEM_ALERT_WINDOW) permission —
     * enabling this Accessibility Service is sufficient.
     *
     * FLAG_NOT_TOUCHABLE + FLAG_NOT_FOCUSABLE mean the overlay is purely
     * visual: touches pass straight through it to whatever is underneath, and
     * it never steals focus. Real "clicks" are injected separately via
     * dispatchGesture(), not by this view intercepting input.
     */
    private fun addCursorOverlay() {
        cursorView = ImageView(this).apply {
            setImageResource(R.drawable.ic_cursor)
        }

        cursorParams = WindowManager.LayoutParams(
            cursorSizePx,
            cursorSizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        updateCursorPosition() // sets cursorParams.x/y from cursorX/cursorY before first add
        windowManager.addView(cursorView, cursorParams)
    }

    /**
     * Small status readout pinned near the top of the screen: shows whether
     * the platform currently reports gesture detection as available, plus a
     * live count/name of raw swipes received. This is the fastest way to
     * tell apart three very different failure modes:
     *   - count never increases           -> sensor isn't reporting swipes to
     *                                         this app at all (often an OEM/
     *                                         HAL limitation, see README)
     *   - "available: false" persists     -> check enrolled fingerprints /
     *                                         sensor is reserved elsewhere
     *   - count increases, cursor doesn't -> a bug in the move/redraw logic
     *                                         (not a sensor problem)
     */
    private fun addDebugOverlay() {
        debugView = TextView(this).apply {
            setBackgroundColor(0xAA000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setPadding(dpToPx(8f), dpToPx(4f), dpToPx(8f), dpToPx(4f))
        }

        debugParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(16f)
            y = dpToPx(56f) // clears the status bar on most devices
        }

        windowManager.addView(debugView, debugParams)
        updateDebugOverlay()
    }

    private fun updateDebugOverlay() {
        if (!::debugView.isInitialized) return
        val availabilityText = when (detectionAvailable) {
            true -> "available"
            false -> "NOT available"
            null -> "checking…"
        }
        debugView.text = "FP gestures: $availabilityText\nreceived: $receivedCount  last: $lastReceivedName"
    }

    /** Human-readable name for a raw gesture constant, for the debug overlay. */
    private fun gestureName(gesture: Int): String = when (gesture) {
        FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_UP -> "UP"
        FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_DOWN -> "DOWN"
        FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_LEFT -> "LEFT"
        FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_RIGHT -> "RIGHT"
        else -> "unknown($gesture)"
    }

    /**
     * Core input handler. Every raw swipe either moves the cursor by one
     * step, or — if it repeats the previous direction quickly enough —
     * is reinterpreted as a click at the cursor's current position.
     */
    private fun handleGesture(gesture: Int) {
        receivedCount++
        lastReceivedName = gestureName(gesture)
        updateDebugOverlay()

        val now = SystemClock.uptimeMillis()
        val isDoubleSwipe = gesture == lastGestureType &&
            (now - lastGestureAtMs) <= DOUBLE_SWIPE_WINDOW_MS

        if (isDoubleSwipe) {
            performClickAtCursor()
            // Reset tracking so a third quick swipe starts a fresh sequence
            // instead of firing another click immediately.
            lastGestureType = -1
            lastGestureAtMs = 0L
            return
        }

        lastGestureType = gesture
        lastGestureAtMs = now

        when (gesture) {
            FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_UP -> cursorY -= moveStepPx
            FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_DOWN -> cursorY += moveStepPx
            FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_LEFT -> cursorX -= moveStepPx
            FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_RIGHT -> cursorX += moveStepPx
            else -> {
                Log.w(TAG, "Unhandled fingerprint gesture constant: $gesture")
                return
            }
        }

        // Clamp so the cursor can't be nudged off the visible display.
        cursorX = cursorX.coerceIn(0, screenWidthPx)
        cursorY = cursorY.coerceIn(0, screenHeightPx)

        updateCursorPosition()
    }

    /**
     * Pushes cursorX/cursorY into the overlay's WindowManager.LayoutParams.
     *
     * cursorX/cursorY represent where the pointer's TIP should visually sit.
     * LayoutParams.x/y, with Gravity.TOP|START, position the view's TOP-LEFT
     * corner. So we subtract half the icon's size from each axis — this is
     * the only coordinate translation happening: logical pointer position
     * -> top-left corner of a CURSOR_SIZE_DP x CURSOR_SIZE_DP view.
     */
    private fun updateCursorPosition() {
        cursorParams.x = cursorX - cursorSizePx / 2
        cursorParams.y = cursorY - cursorSizePx / 2

        if (::cursorView.isInitialized && cursorView.isAttachedToWindow) {
            windowManager.updateViewLayout(cursorView, cursorParams)
        }
    }

    /**
     * Dispatches a single, short tap gesture at the cursor's current
     * coordinates — this is what actually "clicks" whatever is underneath
     * the pointer in the foreground app.
     */
    private fun performClickAtCursor(): Boolean {
        val tapPath = Path().apply {
            moveTo(cursorX.toFloat(), cursorY.toFloat())
        }

        val stroke = GestureDescription.StrokeDescription(
            tapPath,
            0L,
            CLICK_STROKE_DURATION_MS
        )

        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                super.onCompleted(gestureDescription)
                playClickFeedback()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                Log.w(TAG, "Click gesture was cancelled by the system.")
            }
        }, null)
    }

    /** Brief scale "pulse" on the cursor icon so a click has visible feedback. */
    private fun playClickFeedback() {
        if (!::cursorView.isInitialized) return
        ObjectAnimator.ofFloat(cursorView, "scaleX", 1f, 1.6f, 1f).setDuration(180).start()
        ObjectAnimator.ofFloat(cursorView, "scaleY", 1f, 1.6f, 1f).setDuration(180).start()
    }

    private fun dpToPx(dp: Float): Int {
        return (dp * resources.displayMetrics.density).roundToInt()
    }

    // We don't need general accessibility events (window content, clicks
    // elsewhere, etc.) for this app — all of our input comes through the
    // fingerprint gesture callback instead. Required override, intentionally empty.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted by the system.")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        gestureController?.unregisterFingerprintGestureCallback(fingerprintCallback)
        if (::cursorView.isInitialized && cursorView.isAttachedToWindow) {
            windowManager.removeView(cursorView)
        }
        if (::debugView.isInitialized && debugView.isAttachedToWindow) {
            windowManager.removeView(debugView)
        }
        return super.onUnbind(intent)
    }
}
