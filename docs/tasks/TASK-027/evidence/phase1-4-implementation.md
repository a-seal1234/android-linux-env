# TASK-027 Phases 1–4 — implementation & test record (2026-11-19)

**Owner / harness:** CommandCode / local Linux environment · branch `main`
**Gate context:** GPU Rendering Performance Blocker Gate **passed for practical usability**
(~30 FPS sustained active rendering on the Xiaomi Pad 8, `192.168.51.161:44021`), recorded at
the top of [TASKS.md](../../../TASKS.md). TASK-029 zero-copy verification deferred, no longer gating.

## Build & tests

- `./gradlew test assembleDebug` → **BUILD SUCCESSFUL**
- Workspace unit tests (debug variants, all modules): **211 tests, 0 skipped, 0 failures, 0 errors**
  (release variants green as well)

New/extended tests:

| Test | Asserts |
|---|---|
| `InputDispatcherTest.testEvdevTableCoversFunctionRowNavigationAndSystemKeys` | F1–F12 (59–68, 87, 88), Home 102 / End 107 / PgUp 104 / PgDn 109 / Insert 110 / Del 111, SysRq 99, ScrollLock 70, Pause 119, Menu→Compose 127 |
| `InputDispatcherTest.testEvdevTableCoversCompleteNumpadBlock` | Numpad 0–9 (82, 79–81, 75–77, 71–73), dot 83, enter 96, + 78, − 74, × 55, ÷ 98, NumLock 69 |
| `InputDispatcherTest.testEvdevTableCoversModifiersAlphabetDigitsAndIso102nd` | Both-side Shift/Ctrl/Alt/Super (42/54, 29/97, 56/100, 125/126), CapsLock 58, ISO 102nd 86, A–Z, digits, controls, Back→Esc |
| `InputDispatcherTest.testKeyboardFallbackUsesEvdevTableForFunctionKeys` / `...NavigationKeys` | scanCode==0 events dispatch through the table (F5→63, PgDn→109) |
| `AccessibilityHealerTest` (7 tests) | Setting-string parse/merge: both stored spellings, null/blank/"null" tolerance, append-without-duplicate, byte-preserving merge, canonical constants |

## Landed files

- `app/src/main/java/com/alw/workstation/service/AlwKeyInterceptService.kt` (new)
- `app/src/main/java/com/alw/workstation/service/AccessibilityHealer.kt` (new)
- `app/src/main/res/xml/alw_key_intercept_config.xml`, `app/src/main/res/values/strings.xml` (new)
- `app/src/main/AndroidManifest.xml` — `BIND_ACCESSIBILITY_SERVICE` service + `WRITE_SECURE_SETTINGS`
- `app/src/main/java/com/alw/workstation/MainActivity.kt` — `onResume` heal, `onDestroy` teardown, grant banner
- `bridge/wayland/src/main/java/.../input/SessionInputBus.kt` (new), `input/EvdevKeyMap.kt` (new)
- `bridge/wayland/src/main/java/.../WaylandSurfaceView.kt` — `isSessionFocused` gate, key sink,
  focus-loss modifier clear + pointer-capture release
- `bridge/wayland/src/main/java/.../WaylandServerManager.kt` — `clearKeyboardModifiers()` JNI
- `bridge/wayland/src/main/cpp/wayland_bridge.cpp` — `clearKeyboardModifiers()` (zero
  `m_depressedModifiers`, re-broadcast deduped `wl_keyboard.modifiers`, preserve CapsLock lock),
  JNI export, `pthread_setname_np` for `alw-wayland-event` / `alw-wayland-pacing`
- `bridge/wayland/src/main/cpp/audio_bridge.cpp` — `alw-audio-loop` thread name
- `bridge/wayland/src/main/java/.../InputDispatcher.kt` — table extraction to `EvdevKeyMap`
- `app/src/main/java/.../ui/dashboard/DashboardViewModel.kt` — scope-corrected Shizuku
  collector, audio `coordinateWith` job dedupe, `destroy()`, stop-session focus clear
- `core/session/src/main/java/.../rice/RiceHostBridge.kt` — sliced sysfs sleep (joinable stop)

## Fallback preserved

`AlwKeyInterceptService` only consumes keys when `SessionInputBus.isSessionFocused` is true
(surface live AND window focused). Without the a11y grant (or after dismissal) the hardware
keyboard path (`MainActivity.dispatchKeyEvent` → `WaylandSurfaceView.onKeyDown` →
`InputDispatcher`) is untouched.

## Outstanding (Phases 5–6, on-device)

SUPER+Return/Q/M, Alt+Tab, Esc capture in-guest (`wev`); nano Ctrl+X/Ctrl+C; one-time ADB
grant + heal verification on the Pad 8; right/middle click + wheel + GAMING_RAW capture; the
no-swallow check (Home → Super stays Android).
