# TapFlow

Clean-room auto clicker for Android, written in Kotlin (Jetpack Compose UI + plain Views for the overlay).
Not derived from any third-party app's code, strings, icons or branding.

## Features
- Floating control panel with fixed rows (grip + status + close; labelled **Start / Pause / Resume** and **Stop**;
  tools: add tap, add swipe, delete last, quick save, open app), draggable by the grip or the status text.
  Start becomes Pause while running and Resume while paused; Stop ends the run. Pause keeps progress and Resume continues
  at the step that was interrupted (and the rest of a start countdown). Quick save overwrites the loaded script, otherwise adds "Script N".
- Long-press a marker for a small **Clone / Delete** menu (a tap elsewhere closes it; dragging after the press cancels it).
  Clone puts a copy next to the original in the same step, so the two run together (labels 2a / 2b); the Home target editor has
  the same Clone button. Deleting (marker menu or panel trash) can be undone by tapping the panel's status line for 6 s.
- The panel has a **minimize** button: it folds into a small draggable bubble (a green dot says a run is going; tap it to
  bring the panel back). The run keeps going while minimized; markers stay as they were.
- The overlay (panel, bubble, markers) is taken down while the screen is off, in the always-on display and on the lock screen,
  and comes back, in the same place, after unlock. The Quick Settings tile asks for unlock before it does anything.
- Home screen is a control centre: one card says what is going on and what to press next (setup help, a 3-step guide with no
  targets, the plan "3 targets · every 1 s · until you stop" with Start / Show panel, Pause / Stop while running,
  Resume / Stop while paused, Run again after a run). Start from the app shows the panel, hides the app and counts down at least 2 s.
- Per-target order: targets sharing the same order number are performed together as one multi-touch gesture; tap types
  Tap / Double tap (2 strokes in one gesture, configurable gap) / Long press (press time presets); each can be swipes too.
- Numbered draggable markers; long-press a marker to delete it. Swipes have start and end markers joined by an arrow.
- Multi-target sequences, interval (ms/s/min, presets), stop after N cycles or a time limit, start countdown,
  random timing (±%) and random position (±px).
- Saved scripts: save, load, rename, duplicate, delete, import/export as JSON.
- Test mode tab: full-screen black stage with the real floating panel. Every touch it receives (finger or injected
  by the engine) lights up: glowing ring for taps, trail + start/end dots for swipes, numbered by order, with live
  tap/swipe counters. Leaving the tab (or the app) stops any run; the panel is hidden again if test mode showed it.
- Test mode also labels each touch with the matching target number and measures the real start-to-start gap
  (avg/min/max) against the configured interval; the gap includes the gesture's own press time.
- Quick Settings tile (start/stop the current targets; opens the app if the service is off).
- Modern minimalist UI: neutral surfaces, hairline outlines, one accent colour, Material 3 components behind thin
  wrappers in `ui/Design.kt` and the palette in `ui/Theme.kt`. The overlay panel and markers follow the same palette
  (light/dark from the system); marker shape tells the kind apart (circle = tap, square = swipe start, outlined square = swipe end).
- Setup guide that covers "Allow restricted settings" (sideloaded installs on Android 13+).
- English + Vietnamese, following the system language (per-app language via `locales_config.xml`); light/dark.
- No ads, no analytics, no internet permission.

## Build
```
.\gradlew.bat assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
.\gradlew.bat assembleRelease   # minified, unsigned
```
Requires JDK 17+, Android SDK with platform 37, AGP 9.4.1 / Gradle 9.6.

```
.\gradlew.bat :app:testDebugUnitTest   # JVM tests: steps/labels/sanitising, stroke packing, JSON round trip + legacy files
```
Note: `installDebug` installs on every connected device; set `ANDROID_SERIAL` (or use `adb -s <serial> install`) when a real phone is attached.

## Design notes and edge cases handled
- Overlay uses `TYPE_ACCESSIBILITY_OVERLAY`, so no `SYSTEM_ALERT_WINDOW` permission and the injected touches are trusted.
- Coordinates are stored normalized (0..1) and re-resolved at dispatch time, so rotation / resolution changes keep working.
- Markers become non-touchable and translucent while running so injected taps never hit the overlay.
- One gesture in flight at a time (waits for completed/cancelled, with timeout); interval floor 20 ms; coordinates clamped to the display;
  gesture duration clamped to `getMaxGestureDuration()`; run stops after 10 consecutive rejected gestures.
- Run stops on screen-off, service unbind/destroy, Volume Down (hardware key, optional), notification Stop button (optional), or the panel Stop.
- App state recovers when the service was killed (STALE state with instructions) and when the process dies (working draft persisted).
- Numeric inputs are validated with inline errors; imports are size-capped and schema-validated; corrupt storage never crashes start-up.

- Feedback inside other apps goes into the panel's status line, not Toasts: Android suppresses toasts from apps without
  notification permission while another app is in the foreground.
- A run refuses to start if a target sits under the panel (the injected tap would hit the panel itself).
- Per-target press/swipe time and optional per-target delay (blank = global interval).
- The notification is only re-posted on state changes (none/active/paused), never per click.

## Verified on an Android 16 (API 36) emulator
Gesture dispatch (taps and swipes lit up and counted by Test mode), hardware Volume Down stop (and not swallowed while idle),
screen-off stop, rotation to landscape, marker drag / long-press delete / draft persistence, service-lost (STALE) recovery,
Vietnamese per-app language for both the app and the overlay, dark mode, font scale 1.3 (Home, panel), a 320 dp-wide screen
(panel fits), input validation, IME insets, stop notification, per-target delay, remove + undo from the panel, pause/resume
labels (resume continues at the interrupted step), marker long-press menu with Clone (2a/2b run together), minimize to a bubble
while a run is going (Test mode still lights up the injected taps), overlay hidden on the lock screen and back after unlock
(PIN lock on the emulator), 14 JVM unit tests, release build (R8, 1.4 MB), lint (0 errors).

## Notes on multi-touch
- A step is sent as one `GestureDescription`; steps with more strokes than `getMaxStrokeCount()` are split into consecutive
  gestures, never in the middle of a target (a double tap's strokes stay together).
- Older scripts/drafts without `step` run in list order (step = index + 1).
- Verified on the emulator: two targets with the same order light up together in Test mode, a double tap shows `×2`.

## Not verified
The always-on display itself (the emulator has none; the same SCREEN_OFF broadcast is used), landscape with the 3-row panel,
a clone of a swipe on a device, the bubble when it sits on top of a target during a run (a run refuses to start in that case, but
dragging it there mid-run makes the next tap hit the bubble),
Android below 13 (the `readNBytes` import bug was found by review and fixed; lint reports no `NewApi`), real OEM devices
(MIUI/ColorOS auto-start kills), display cutouts, script import/export through the system file picker, the
"Allow restricted settings" flow on a sideloaded install, folding / split-screen, and signing for distribution
(the release APK is unsigned).

## Known limits
- Gestures cannot be injected into secure windows (banking apps, some system dialogs); the run stops after repeated rejections.
- Force-stopping the app from system settings removes the service from the enabled list; turn it on again in Accessibility settings.
- Some OEMs need auto-start / battery exclusions; Settings → Troubleshooting has shortcuts.
- The original app's Room schema could not be recovered (obfuscated), so storage and JSON format are this project's own.
