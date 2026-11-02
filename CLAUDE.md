# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

TapFlow: clean-room Android auto clicker (Kotlin, Compose UI + plain-View overlay). Single module `:app`, package `app.tapflow`, minSdk 26 / targetSdk 36 / compileSdk 37. Not derived from any third-party app; keep it that way (no copied code, strings, icons, branding). `README.md` has the full feature list, verified/not-verified matrix and known limits.

## Commands

Run from repo root (PowerShell; JDK 17+, Android SDK platform 37):

```
.\gradlew.bat assembleDebug                      # app/build/outputs/apk/debug/app-debug.apk
.\gradlew.bat assembleRelease                    # R8 minified, unsigned
.\gradlew.bat :app:testDebugUnitTest             # all JVM unit tests
.\gradlew.bat :app:testDebugUnitTest --tests "app.tapflow.ModelsTest"   # one test class
.\gradlew.bat :app:lintDebug                     # lint (abortOnError = false, check report)
```

`installDebug` installs on every connected device; set `ANDROID_SERIAL` (or `adb -s <serial> install`) when a real phone is attached. Unit tests only cover `model/` and `data/` (JSON round trip, step/label logic, sanitising); service/overlay/UI behaviour is verified manually on an emulator. Tests use `org.json:json` because the android.jar stub is empty on the JVM.

## Architecture

Two halves in one process, glued by the `Session` singleton (`Session.kt`):

- **UI half** (`MainActivity`, `ui/*`, Compose Material 3 via thin wrappers in `ui/Design.kt`, palette in `ui/Theme.kt`): Home, Scripts, Test mode, Settings, Guide screens.
- **Service half** (`service/*`): `ClickerService` is an `AccessibilityService` (no event types, never reads window content). It owns:
  - `ClickEngine`: coroutine loop that builds `GestureDescription`s and injects them via `dispatchGesture`. One gesture in flight at a time (next sent only after completed/cancelled callback), pause/resume state (`resumeStep`, `countdownLeft`, `activeMs`).
  - `OverlayController` + `OverlayViews.kt`: floating panel, minimized bubble, draggable numbered markers, clone/delete menu. Plain Android Views (not Compose) added as `TYPE_ACCESSIBILITY_OVERLAY`, which needs no `SYSTEM_ALERT_WINDOW` and makes injected touches trusted.
  - Screen-off / lock handling via broadcasts in `ClickerService` (overlay suppressed, run stopped); `RunTileService` is the Quick Settings tile.

`Session` holds `MutableStateFlow`s (`targets`, `config`, `run`, `stats`, `serviceConnected`, `overlayVisible`) that both halves observe; the service collects them to re-render the overlay, the UI collects them via `lifecycle-runtime-compose`. State changes go through `Session`, not direct calls between UI and overlay. `Session.updateConfig` always runs `RunConfig.sanitized()`. `configEpoch` is bumped on external config replacement so text fields reset.

`model/Models.kt`: `Target` (TAP/SWIPE, normalized 0..1 coordinates, per-target `step` order, press/swipe time, delay), `RunConfig`, `Script`, `RunState` (Idle/Countdown/Running/Paused/Finished), `Limits`. Targets with the same `step` run together as one multi-touch gesture (`steps()` groups them; `TargetLabel` gives 2a/2b style labels). Steps with more strokes than `getMaxStrokeCount()` are split between targets, never inside one (double tap strokes stay together). Old data without `step` falls back to list order.

Persistence: `data/Store.kt` (draft + saved scripts + settings), `data/ScriptJson.kt` (import/export, size-capped and schema-validated, tolerates legacy files). `AppSettings.kt` holds user toggles. Corrupt storage must never crash start-up.

## Conventions and gotchas

- Coordinates are stored normalized and resolved to pixels at dispatch time (`ScreenMetrics`), so rotation/resolution changes work. Clamp to display, interval floor 20 ms, clamp duration to `getMaxGestureDuration()`.
- Markers must be non-touchable while running so injected taps never hit the overlay; a run refuses to start if a target sits under the panel.
- Feedback shown while another app is foreground goes to the panel status line, not Toasts (suppressed without notification permission).
- Notification is re-posted only on state change (none/active/paused), never per click.
- User-facing strings live in `res/values/strings.xml` and `res/values-vi/strings.xml` (English + Vietnamese, per-app language via `xml/locales_config.xml`); the overlay uses them too, so add both.
- Service may be killed or app process may die mid-run: on connect, a stale busy `RunState` becomes `Finished(SERVICE_LOST)`.
