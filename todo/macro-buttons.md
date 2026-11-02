# Macro buttons

Status: planned, not started.

## Goal

Floating macro button(s) on the overlay. Tapping a button runs a saved script (e.g. a skill combo in a game). Reuses the existing script/engine/overlay stack.

## Chosen direction (option A)

Multiple macro buttons, each bound to one saved script. Not a single button, and no touch recording in v1.

## Reuse

- `Script` (targets, steps, delays) is the macro payload.
- `ClickEngine` runs it; needs a "run once then stop" path.
- `OverlayController` / `OverlayViews.kt` already draw draggable bubble and markers; add a macro button view in the same style.

## Work items

1. Model: `MacroButton` (normalized x/y, label, color, `scriptId`, `mode`) in `model/Models.kt`. Mode enum:
   - `ONCE`: tap runs one pass (default).
   - `TOGGLE`: tap starts repeat loop, tap again stops.
   - `HOLD`: repeats while pressed, stops on release.
2. Persistence: store in `data/Store.kt`; include in `data/ScriptJson.kt` import/export (schema-validated, size-capped, tolerate files without macro buttons).
3. `Session`: add `macroButtons` StateFlow; changes go through `Session`, not direct overlay calls.
4. Overlay: render buttons, draggable in edit mode, touchable while a run is active (unlike markers). Refuse/warn when a button overlaps a target of its own script.
5. Engine concurrency (one gesture in flight): decide policy when a button is pressed during another run. Proposed: ignore if busy, show panel status line; revisit if it feels bad in games.
6. UI: screen/section to create, edit, delete buttons, pick script and mode. Strings in both `values/strings.xml` and `values-vi/strings.xml`.
7. Tests: JVM unit tests for model + JSON round trip + sanitising (`ModelsTest` style). Manual emulator check for overlay and run behavior.

## Out of scope (v1)

- Recording real touches into a macro. Needs a full-screen touch-capturing overlay, which blocks the underlying app. Revisit later.

## Open questions

- Busy policy: ignore vs queue vs interrupt.
- Can several macro buttons be visible at once with the main panel, and how are they hidden/shown (per-script or global toggle)?
- Per-button repeat count / interval overrides, or always inherit from the script's `RunConfig`?
