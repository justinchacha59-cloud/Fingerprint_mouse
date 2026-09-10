# Fingerprint Mouse Controller

Turns a phone into a laptop-style trackpad using either fingerprint-sensor
swipes or device tilt (user's choice), with an adjustable speed. Built as a
single AccessibilityService — no root needed.

## Project layout

```
FingerprintMouseController/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/example/fingerprintmouse/
│       │   ├── FingerprintMouseService.kt   <- core logic (read this first)
│       │   ├── ControlMode.kt               <- mode + speed prefs shared with MainActivity
│       │   └── MainActivity.kt
│       └── res/
│           ├── drawable/                    <- cursor icon + launcher icon
│           ├── layout/activity_main.xml
│           ├── mipmap-anydpi-v26/           <- adaptive launcher icon
│           ├── values/strings.xml
│           └── xml/accessibility_service_config.xml
├── .github/workflows/build.yml              <- CI build -> downloadable APK
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## How it works, in one paragraph

Two interchangeable control schemes, picked on the main screen and stored in
SharedPreferences (`ControlMode.kt`) — `FingerprintMouseService` reads
whichever is selected and reacts live if you switch while it's running:

- **Fingerprint swipe**: `FingerprintGestureController` reports one of four
  raw swipes (UP/DOWN/LEFT/RIGHT — that's the entire platform API, there's no
  tap event for the sensor). Swiping the same direction twice within 350ms is
  treated as a click.
- **Tilt**: the phone's orientation is read via `TYPE_ROTATION_VECTOR` — the
  platform's own fusion of the accelerometer, gyroscope, and magnetometer
  into one stable reading, rather than using the raw accelerometer alone —
  and converted into cursor velocity (tilt further, move faster; return to
  level, it stops). Holding **Volume Up** for ~450ms and releasing performs a
  click, via `onKeyEvent()` (requires `canRequestFilterKeyEvents="true"`); a
  quick press does nothing and isn't consumed, so ordinary volume control
  still works for anything shorter than that.

Either way, the cursor itself is the same small arrow icon, redrawn at
`(cursorX, cursorY)` using a `TYPE_ACCESSIBILITY_OVERLAY` window (no separate
"draw over other apps" permission needed), and clicks are dispatched via a
short synthetic tap through `dispatchGesture()`.

A **Cursor speed** slider on the main screen (0.5x–3.0x, stored as
`speed_multiplier` in `ControlModePrefs`) scales movement in both modes: the
distance per fingerprint swipe, and the tilt-to-pixel sensitivity.

## Building it

You don't have a local Android SDK/emulator in Termux, so the fastest path is
the included GitHub Actions workflow rather than trying to build on-device:

1. Push this project to a GitHub repo.
2. The workflow at `.github/workflows/build.yml` runs automatically on push to
   `main` (or trigger it manually from the Actions tab).
3. Download the built APK from the run's **Artifacts** section
   (`fingerprint-mouse-debug-apk`).
4. Transfer the APK to your test device and install it (you'll need to allow
   "install unknown apps" for whichever app you use to open it, and — on
   Android 13+ side-loaded installs — **Allow restricted settings** for this
   app before Accessibility will let you turn the service on).

If you ever do have access to Android Studio: open the project root, let
Gradle sync, and run/debug directly onto a device — no changes needed.

## Enabling it on a device

1. Open the app, flip the switch — it jumps you to
   **Settings → Accessibility**.
2. Find **Fingerprint Mouse Control** in the list and turn it on. You'll see
   the standard Android warning about what accessibility services can do;
   that's expected for any app using `dispatchGesture()`.
3. Pick **Fingerprint swipe** or **Tilt** and adjust the speed slider as you like.
4. Fingerprint mode: swipe the sensor to move, double-swipe same direction to
   click. Tilt mode: tilt the phone to move, hold Volume Up ~450ms and
   release to click.

## Known platform limits worth knowing before you extend this

- **No tap gesture exists on the fingerprint API.** Only the 4 swipe
  directions are reported. The double-swipe-to-click here is a workaround,
  not a hidden API.
- **Fingerprint gesture support is OEM/HAL-dependent.** Some devices'
  fingerprint hardware never reports swipes to any app at all — a
  driver-level gap some budget/mid-range OEM skins never implemented, not
  something fixable from app code. Tilt mode exists specifically as a
  fallback for exactly this case.
- **Volume-key interception can also be OEM-dependent.** `onKeyEvent()`
  should receive Volume Up regardless of press length once
  `canRequestFilterKeyEvents="true"` is set and the service is active, but a
  small number of OEM builds intercept hardware volume keys at a lower level
  before accessibility services ever see them — the same category of
  limitation as fingerprint gestures above, just for a different piece of
  hardware. If long-pressing Volume Up genuinely never clicks (not just
  "clicks in the wrong place"), that's the most likely explanation, and no
  app-level fix exists for it — an on-screen click button would be the
  reliable fallback if you hit this.
- `applicationId`/package is `com.example.fingerprintmouse`, change it
  before you ship this anywhere.
- **Tilt and speed tuning lives in `FingerprintMouseService.kt`'s companion
  object** (`TILT_DEADZONE_RAD`, `TILT_BASE_SENSITIVITY`,
  `TILT_CURVE_EXPONENT`, `TILT_MAX_STEP_DP`, `TILT_INVERT_X/Y`,
  `VOLUME_LONG_PRESS_MS`). Sign conventions for tilt axes vary enough across
  devices/grips that `TILT_INVERT_X/Y` is meant to be hand-tuned — if the
  cursor moves opposite to what feels natural, flip the relevant one first.
