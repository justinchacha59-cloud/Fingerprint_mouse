package com.example.fingerprintmouse

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.FingerprintGestureController
import android.accessibilityservice.FingerprintGestureController.FingerprintGestureCallback
import android.accessibilityservice.GestureDescription
import android.animation.ObjectAnimator
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Turns the phone into a laptop-style trackpad, using either of two input
 * schemes the user picks on the main screen ([ControlMode]):
 *
 *   FINGERPRINT — FingerprintGestureController reports one of four raw
 *   directional swipes (UP/DOWN/LEFT/RIGHT — that's the entire platform API,
 *   there is no "tap" event for the sensor). Swiping the same direction
 *   twice within [DOUBLE_SWIPE_WINDOW_MS] is treated as a click, since a
 *   deliberate double-flick is unlikely to happen by accident during normal
 *   single-step movement. Some devices' fingerprint hardware never reports
 *   these swipes to apps at all — a driver/OEM limitation this code can't
 *   work around — which is what TILT mode exists as an alternative to.
 *
 *   TILT — the device's fused orientation sensor (TYPE_ROTATION_VECTOR,
 *   which is itself the platform combining the accelerometer, gyroscope, and
 *   magnetometer into one stable reading) drives the cursor continuously,
 *   like a joystick: tilt further, move faster; return to level, it stops.
 *   Volume Up performs a click, via [onKeyEvent] — held for
 *   [VOLUME_LONG_PRESS_MS] and released, rather than a quick press, so it
 *   can't be mistaken for (or collide with) a normal volume adjustment.
 *
 * Either way the cursor is the same small overlay icon, redrawn at
 * (cursorX, cursorY) via a TYPE_ACCESSIBILITY_OVERLAY window (no separate
 * "draw over other apps" permission needed), and a click is dispatched as a
 * short synthetic tap through dispatchGesture().
 *
 * [ControlModePrefs] holds the active mode and a user-adjustable speed
 * multiplier (set on the main screen); this service reads both on connect
 * and reacts live if either changes while it's already running.
 */
class FingerprintMouseService : AccessibilityService() {

    companion object {
        private const val TAG = "FingerprintMouseSvc"

        // Visual size of the cursor icon, in dp.
        private const val CURSOR_SIZE_DP = 40f

        // --- Fingerprint mode ---
        // Base distance the cursor moves per single swipe, in dp, before the
        // user's speed multiplier is applied.
        private const val BASE_MOVE_STEP_DP = 48f

        // Two same-direction swipes inside this window = a "click", not two moves.
        private const val DOUBLE_SWIPE_WINDOW_MS = 350L

        // Duration of the synthetic tap stroke dispatched to the system.
        // A real fingertip tap is brief; a very short stroke reads as a tap
        // rather than a long-press to whatever app receives it.
        private const val CLICK_STROKE_DURATION_MS = 40L

        // --- Tilt mode ---
        // Orientation angles from getOrientation() are in RADIANS, roughly
        // -1.57 to 1.57 (-90deg to 90deg). A small deadzone filters natural
        // hand jitter while still starting movement almost the instant you tilt.
        private const val TILT_DEADZONE_RAD = 0.025f // ~1.4 degrees
        // Base px of movement per tick, per (radian past deadzone), before
        // the curve exponent and the user's speed multiplier are applied.
        private const val TILT_BASE_SENSITIVITY = 260f
        // >1 makes larger tilts ramp up faster than linear — small tilts
        // still respond immediately, big tilts cover ground fast.
        private const val TILT_CURVE_EXPONENT = 1.3f
        // Per-tick clamp so a hard tilt can't fling the cursor across the
        // screen in one update, even at high speed-multiplier settings.
        private const val TILT_MAX_STEP_DP = 26f
        // How often we apply a tilt reading to the cursor. The sensor itself
        // may report faster than this; this just caps how often we redraw.
        private const val TILT_UPDATE_INTERVAL_MS = 16L // ~60 updates/sec
        // Flip either of these if the cursor moves opposite to what feels
        // natural on your device/grip — sign conventions for tilt vary
        // enough between devices that this is meant to be tuned by hand.
        private const val TILT_INVERT_X = false
        private const val TILT_INVERT_Y = true

        // Volume Up must be held this long (and then released) to register
        // as a click in tilt mode — deliberately longer than a normal quick
        // press, so it can't be confused with adjusting media volume.
        private const val VOLUME_LONG_PRESS_MS = 450L
    }

    private lateinit var windowManager: WindowManager
    private lateinit var cursorView: ImageView
    private lateinit var cursorParams: WindowManager.LayoutParams

    private var screenWidthPx = 0
    private var screenHeightPx = 0
    private var cursorSizePx = 0
    private var baseMoveStepPx = 0
    private var tiltMaxStepPx = 0

    // Which input scheme is active, and how fast movement is — both read
    // from ControlModePrefs on connect, then kept in sync live via prefsListener.
    private var controlMode: ControlMode = ControlMode.FINGERPRINT
    private var speedMultiplier: Float = ControlModePrefs.DEFAULT_SPEED_MULTIPLIER

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

    // --- Tilt sensor state ---
    private var sensorManager: SensorManager? = null
    private var orientationSensor: Sensor? = null
    private var usingRotationVector = false
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var lastTiltUpdateMs = 0L

    // --- Volume-Up long-press tracking (tilt mode's click button) ---
    private var volumeDownAtMs = 0L

    private val fingerprintCallback = object : FingerprintGestureCallback() {
        override fun onGestureDetected(gesture: Int) {
            handleGesture(gesture)
        }

        override fun onGestureDetectionAvailabilityChanged(available: Boolean) {
            // Common causes for `false`: no fingerprint hardware, no enrolled
            // fingerprints, or the sensor is reserved for a system auth prompt.
            Log.i(TAG, "Fingerprint gesture detection available: $available")
        }
    }

    /** Picks up a mode/speed change made from MainActivity while this service is already running. */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            ControlModePrefs.MODE_KEY -> controlMode = ControlModePrefs.getMode(this)
            ControlModePrefs.SPEED_KEY -> speedMultiplier = ControlModePrefs.getSpeedMultiplier(this)
        }
    }

    private val tiltListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (controlMode != ControlMode.TILT) return

            if (usingRotationVector) {
                // getRotationMatrixFromVector + getOrientation turn the fused
                // rotation vector (accelerometer + gyroscope + magnetometer)
                // into [azimuth, pitch, roll] in radians. pitch = forward/back
                // tilt, roll = left/right tilt — that pairing is what we want
                // for an X/Y cursor.
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                handleTilt(rollRad = orientationAngles[2], pitchRad = orientationAngles[1])
            } else {
                // Fallback path (no rotation-vector sensor on this device):
                // approximate an angle from raw accelerometer m/s^2 so the
                // same radian-based tuning constants still apply sensibly.
                val rollRad = asin((event.values[0] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f))
                val pitchRad = asin((event.values[1] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f))
                handleTilt(rollRad, pitchRad)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            // Not used — tilt is relative movement, not an absolute
            // measurement, so sensor accuracy changes don't matter here.
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val metrics = resources.displayMetrics
        screenWidthPx = metrics.widthPixels
        screenHeightPx = metrics.heightPixels
        cursorSizePx = dpToPx(CURSOR_SIZE_DP)
        baseMoveStepPx = dpToPx(BASE_MOVE_STEP_DP)
        tiltMaxStepPx = dpToPx(TILT_MAX_STEP_DP)

        // Start the pointer in the middle of the screen.
        cursorX = screenWidthPx / 2
        cursorY = screenHeightPx / 2

        controlMode = ControlModePrefs.getMode(this)
        speedMultiplier = ControlModePrefs.getSpeedMultiplier(this)
        ControlModePrefs.registerListener(this, prefsListener)

        addCursorOverlay()
        registerFingerprintGestures()
        registerTiltSensor()
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
            Log.w(TAG, "Fingerprint gesture detection is not available right now.")
        }

        controller.registerFingerprintGestureCallback(fingerprintCallback, null)
    }

    /**
     * Registers the tilt sensor used by [ControlMode.TILT]. Prefers
     * TYPE_ROTATION_VECTOR — the platform's own fusion of accelerometer +
     * gyroscope + magnetometer into one stable orientation reading, which is
     * far more accurate and jitter-free than using the raw accelerometer
     * alone. Falls back to the raw accelerometer only if a device genuinely
     * lacks a rotation-vector sensor (rare on anything from the last decade).
     *
     * Registered unconditionally (not only while tilt mode is selected) so
     * switching modes at runtime is instant — tiltListener itself checks
     * controlMode before acting on each reading.
     */
    private fun registerTiltSensor() {
        val sm = getSystemService(SENSOR_SERVICE) as? SensorManager
        sensorManager = sm

        val rotationVector = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationVector != null) {
            orientationSensor = rotationVector
            usingRotationVector = true
        } else {
            Log.w(TAG, "No rotation vector sensor; falling back to raw accelerometer for tilt.")
            orientationSensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            usingRotationVector = false
        }

        if (orientationSensor == null) {
            Log.w(TAG, "No usable tilt sensor on this device; tilt mode will not function.")
            return
        }

        // FASTEST rather than GAME: tilt mode is explicitly meant to feel
        // immediate, and TILT_UPDATE_INTERVAL_MS below already caps how
        // often we actually redraw, so this just avoids adding the delay
        // that GAME/UI/NORMAL each intentionally introduce.
        sm?.registerListener(tiltListener, orientationSensor, SensorManager.SENSOR_DELAY_FASTEST)
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
     * Core input handler for fingerprint swipes. Every raw swipe either
     * moves the cursor by one step, or — if it repeats the previous
     * direction quickly enough — is reinterpreted as a click.
     */
    private fun handleGesture(gesture: Int) {
        if (controlMode != ControlMode.FINGERPRINT) return

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

        val stepPx = (baseMoveStepPx * speedMultiplier).roundToInt()

        when (gesture) {
            FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_UP -> cursorY -= stepPx
            FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_DOWN -> cursorY += stepPx
            FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_LEFT -> cursorX -= stepPx
            FingerprintGestureController.FINGERPRINT_GESTURE_SWIPE_RIGHT -> cursorX += stepPx
            else -> {
                Log.w(TAG, "Unhandled fingerprint gesture constant: $gesture")
                return
            }
        }

        cursorX = cursorX.coerceIn(0, screenWidthPx)
        cursorY = cursorY.coerceIn(0, screenHeightPx)
        updateCursorPosition()
    }

    /**
     * Continuous counterpart to [handleGesture] for [ControlMode.TILT].
     * Treats "how far past level, in radians" as a velocity rather than a
     * one-shot step: hold the phone tilted and the cursor keeps moving;
     * return it to level and it stops. TILT_CURVE_EXPONENT > 1 means small
     * tilts already produce visible, immediate movement, while larger tilts
     * ramp up faster than linear rather than scaling 1:1.
     */
    private fun handleTilt(rollRad: Float, pitchRad: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTiltUpdateMs < TILT_UPDATE_INTERVAL_MS) return
        lastTiltUpdateMs = now

        val x = if (TILT_INVERT_X) -rollRad else rollRad
        val y = if (TILT_INVERT_Y) -pitchRad else pitchRad

        var moved = false

        if (abs(x) > TILT_DEADZONE_RAD) {
            val past = abs(x) - TILT_DEADZONE_RAD
            val delta = (past.pow(TILT_CURVE_EXPONENT) * TILT_BASE_SENSITIVITY * speedMultiplier)
                .coerceAtMost(tiltMaxStepPx.toFloat())
                .roundToInt()
            cursorX += if (x > 0) delta else -delta
            moved = true
        }

        if (abs(y) > TILT_DEADZONE_RAD) {
            val past = abs(y) - TILT_DEADZONE_RAD
            val delta = (past.pow(TILT_CURVE_EXPONENT) * TILT_BASE_SENSITIVITY * speedMultiplier)
                .coerceAtMost(tiltMaxStepPx.toFloat())
                .roundToInt()
            cursorY += if (y > 0) delta else -delta
            moved = true
        }

        if (!moved) return

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
    // fingerprint gesture callback and the tilt sensor instead. Required
    // override, intentionally empty.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted by the system.")
    }

    /**
     * Only invoked at all because canRequestFilterKeyEvents="true" is set in
     * accessibility_service_config.xml. In [ControlMode.TILT], holding
     * Volume Up for [VOLUME_LONG_PRESS_MS] and releasing it performs a click
     * — deliberately a long-press rather than a quick tap, so it can't be
     * confused with (or accidentally trigger) a normal volume change. We
     * consume the key entirely while in tilt mode, on both press and
     * release, so the system volume UI never appears and the media volume
     * never actually changes. In fingerprint mode we return false for
     * everything, so the volume keys behave completely normally — that
     * mode's click gesture is the double-swipe instead (see [handleGesture]).
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (controlMode != ControlMode.TILT || event.keyCode != KeyEvent.KEYCODE_VOLUME_UP) {
            return false
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    volumeDownAtMs = SystemClock.uptimeMillis()
                }
            }
            KeyEvent.ACTION_UP -> {
                val heldMs = SystemClock.uptimeMillis() - volumeDownAtMs
                if (heldMs >= VOLUME_LONG_PRESS_MS) {
                    performClickAtCursor()
                }
            }
        }

        return true
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        gestureController?.unregisterFingerprintGestureCallback(fingerprintCallback)
        sensorManager?.unregisterListener(tiltListener)
        ControlModePrefs.unregisterListener(this, prefsListener)
        if (::cursorView.isInitialized && cursorView.isAttachedToWindow) {
            windowManager.removeView(cursorView)
        }
        return super.onUnbind(intent)
    }
}
