# Fingerprint Mouse Controller

Turns a phone's capacitive fingerprint sensor into a laptop-style trackpad:
swipe on the sensor to move an on-screen pointer, double-swipe the same
direction to click. Built as a single AccessibilityService — no root needed.

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

`FingerprintMouseService` registers a `FingerprintGestureController` callback
that reports one of four raw swipes (UP/DOWN/LEFT/RIGHT — that's the entire
platform API, there's no tap event for the sensor). Each swipe nudges an
in-memory `(cursorX, cursorY)` and redraws a small arrow icon at that position
using a `TYPE_ACCESSIBILITY_OVERLAY` window (no separate "draw over other
apps" permission needed, since it's an accessibility-service-only window
type). Swiping the same direction twice within 350ms is treated as a click
and dispatches a short synthetic tap via `dispatchGesture()` at the pointer's
current coordinates.

## Building it

You don't have a local Android SDK/emulator in Termux, so the fastest path is
the included GitHub Actions workflow rather than trying to build on-device:

1. Push this project to a GitHub repo.
2. The workflow at `.github/workflows/build.yml` runs automatically on push to
   `main` (or trigger it manually from the Actions tab).
3. Download the built APK from the run's **Artifacts** section
   (`fingerprint-mouse-debug-apk`).
4. Transfer the APK to your test device and install it (you'll need to allow
   "install unknown apps" for whichever app you use to open it).

If you ever do have access to Android Studio: open the project root, let
Gradle sync, and run/debug directly onto a device — no changes needed.

## Enabling it on a device

1. Open the app, flip the switch — it jumps you to
   **Settings → Accessibility**.
2. Find **Fingerprint Mouse Control** in the list and turn it on. You'll see
   the standard Android warning about what accessibility services can do;
   that's expected for any app using `dispatchGesture()`.
3. Go to any screen and swipe on the fingerprint sensor. A small blue-and-white
   arrow should appear and move with your swipes.
4. Swipe the same direction twice quickly to click at the pointer's position.

## Known platform limits worth knowing before you extend this

- **No tap gesture exists on the fingerprint API.** Only the 4 swipe
  directions are reported. The double-swipe-to-click here is a workaround,
  not a hidden API — see the big comment at the top of
  `FingerprintMouseService.kt` if you want to change the click trigger (e.g.
  reserve one direction solely for clicking instead of double-swiping).
- **Sensor availability isn't guaranteed.** `isGestureDetectionAvailable`
  can flip to `false` at runtime (e.g. while a fingerprint auth prompt is on
  screen elsewhere), independent of whether the user granted the
  accessibility permission. The service logs this but keeps the registration
  active so it recovers automatically.
- **In-display (under-screen) fingerprint sensors behave inconsistently
  across OEMs** for this API — some don't report gestures at all, since the
  gesture gets consumed as a general touch/tap on the display digitizer
  instead of routed through the fingerprint HAL. Test on your actual target
  device(s) early.
- `applicationId`/package is `com.example.fingerprintmouse` — change it
  before you ship this anywhere.
