# TASK-027 — In-App Android-to-Wayland Input Bridge (`evdev`, AccessibilityService, Pointer Capture & Overlay Cursor)

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Input Subsystem, Accessibility Integration & Pointer Semantics  
**Board:** [Central task board](../../../TASKS.md) is the workflow-status source.  
**Owner / harness:** Antigravity / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Write scope:** `app/`, `bridge/wayland/`, `docs/tasks/TASK-027/`  
**Risk:** High  
**Status:** **In Progress** — claimed 2026-11-19 (CommandCode / local Linux) after the GPU Blocker Gate passed for practical usability (~30 FPS active). Phases 1–4 implemented and unit-tested; Phases 5–6 (on-device key/pointer verification) pending.  
**Depends on:** TASK-008 (pointer capture, scancode remap, tablet-v2 engine), TASK-026 Phase 0 (metaState→XKB mods path, sticky-CTRL HUD latch).  
**Fallback preserved:** the hardware-keyboard path (`MainActivity.dispatchKeyEvent` → `WaylandSurfaceView.onKeyDown` → `InputDispatcher`) must keep working unchanged when the AccessibilityService is not granted/enabled.

---

## 1. Problem Statement

HyperOS's `PhoneWindowManager` intercepts system-level keys before they reach the activity:
`Super` (META), `Alt+Tab` and `Esc`/Back never arrive at the Wayland guest, so CachyOS
`SUPER`-based Hyprland binds are unreachable from the keyboard. Additionally:

- `InputDispatcher.mapKeyCodeToScanCode` covers A–Z, 0–9, core symbols and modifiers, but lacks
  **F1–F12** (60% keyboards), navigation (Home/End/PgUp/PgDn/Insert), SysRq/ScrollLock/Pause,
  the numpad block and the ISO 102nd key.
- Interception must be **session-scoped**: the app must never swallow system keys when the
  Wayland session is not focused.
- Pointer semantics (right/middle click, scroll, relative capture, touch-vs-pointer separation,
  overlay cursor) exist since TASK-008/TASK-025 but need on-device verification and the
  capture lifecycle tied to session focus.

## 2. Architecture — input paths

```
┌────────────────────────────── Android host ──────────────────────────────────┐
│  Hardware keyboard ──► MainActivity.dispatchKeyEvent ──┐                     │
│  (outside view)                                        │                     │
│  Hardware keyboard ──► WaylandSurfaceView.onKey* ──────┤                     │
│  AccessibilityService (AlwKeyInterceptService)         ▼                     │
│    onKeyEvent ── gate: WaylandSurfaceView.isSessionFocused ──► InputDispatcher│
│  Touch/Mouse/Stylus ──► WaylandSurfaceView.on*MotionEvent ───► InputDispatcher│
│                                                 │                            │
│              ┌───────────────────────────────────┘                            │
│              ▼  WaylandServerManager (JNI)                                    │
│  nativeSendKeyEvent(scanCode, isDown, metaState) → wl_keyboard.key +          │
│      wl_keyboard.modifiers (mods_depressed: Shift 0x1, Ctrl 0x4, Alt 0x8,     │
│      Super 0x40) — change-deduped via client.lastSentModifiers                │
│  nativeSendPointer*  → wl_pointer motion/button (BTN_LEFT 0x110,              │
│      BTN_RIGHT 0x111, BTN_MIDDLE 0x112) + wl_pointer.axis (0=V, 1=H)          │
│  nativeSendTouchEvent → wl_touch down/motion/up (finger ≠ pointer)            │
│  tablet-v2 dispatchers (stylus, TASK-008) — untouched                         │
└───────────────────────────────────────────────────────────────────────────────┘
```

### 2.1 Session-scoped key interception

- `app/src/main/java/com/alw/workstation/service/AlwKeyInterceptService.kt`:
  `AccessibilityService` configuring `FLAG_REQUEST_FILTER_KEY_EVENTS` +
  `CAN_REQUEST_FILTER_KEY_EVENTS`, `eventTypes = 0`, `feedbackType = GENERIC`.
- `onKeyEvent` returns `false` **immediately** unless the static `SessionInputBus` reports
  `WaylandSurfaceView.isSessionFocused == true` (new public flag on `WaylandSurfaceView`,
  set true on `surfaceCreated` + window focus, false on `surfaceDestroyed` / session stop /
  `onWindowFocusChanged(false)`).
- When focused, the event is forwarded to `InputDispatcher.handleKeyEvent` (the same path as
  hardware keys) and consumed; `KEYCODE_BACK` maps to Esc only while focused.
- Targets captured before HyperOS: `Super` (`KEYCODE_META_LEFT/RIGHT`), `Alt+Tab`, `Esc`.

### 2.2 Auto-heal on `MainActivity.onResume()`

- New `AccessibilityHealer` (app module): reads
  `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`; if
  `com.alw.workstation/.service.AlwKeyInterceptService` is missing:
  - **Shizuku path** (existing UID 2000 companion, TASK-014): run
    `settings put secure enabled_accessibility_services <existing>:<component>` +
    `settings put secure accessibility_enabled 1` through Shizuku — verify the exact
    shizuku-api shell surface against the installed library version before coding.
  - **ADB fallback**: Compose banner with a copyable block:
    `adb -s 192.168.51.161:44021 shell pm grant com.alw.workstation android.permission.WRITE_SECURE_SETTINGS`
    followed by the two `settings put secure …` commands.
- Edge cases: preserve pre-existing enabled services, never duplicate the entry, re-validate
  after the grant, re-heal after app updates/reboots (HyperOS may drop the service).
- Manifest: `<service android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">`
  + `<meta-data … android.accessibilityservice>` pointing at
  `res/xml/alw_key_intercept_config.xml`; `WRITE_SECURE_SETTINGS` uses-permission.

### 2.3 Complete `evdev` table & XKB `Mod4` sync

- Convert `mapKeyCodeToScanCode` to an explicit `Map<Int, Int>` (Android `KEYCODE_*` → Linux
  evdev code from `linux/input-event-codes.h`), unit-testable in full. Hardware `scanCode > 0`
  still wins as ground truth (existing behaviour).
- Additions: F1–F12 (59–68, 87, 88), Home 102 / End 107 / PgUp 104 / PgDn 109 / Insert 110,
  SysRq 99, ScrollLock 70, Pause 119, numpad 0–9 (82, 79, 80, 81, 75, 76, 77, 71, 72, 73) +
  NumDot 83 / NumEnter 96 / Num+ 78 / Num− 74 / Num× 55 / Num÷ 98, ISO 102nd 86,
  Menu/Compose 127.
- `mods_depressed` sync (Phase 0 of TASK-026 landed the merge): verify Android's generic meta
  bits cover right-side modifiers (right-Shift/Ctrl/Alt also set the generic bit);
  stickiness hygiene — the HUD CTRL latch and the a11y path share one modifier state so a
  latch or focus loss cannot leave a stuck bit (synthesize key-up on `isSessionFocused=false`);
  keep the `lastSentModifiers` change-dedupe.

### 2.4 Pointer capture & overlay cursor (verification + lifecycle)

- `requestPointerCapture()` (GAMING_RAW profile): also request/release capture on
  `isSessionFocused` transitions; relative deltas flow via `AXIS_RELATIVE_X/Y`.
- Verify on-device: right/middle click → `BTN_RIGHT`/`BTN_MIDDLE` (confirm the native
  bit→button map in `wayland_bridge.cpp`), wheel → `wl_pointer.axis` (axis 0/1 already sent),
  `wl_touch` vs `wl_pointer` separation unchanged (finger = `wl_touch` + single-finger pointer
  emulation), cursor sprite composited at the panel rate with `requestUnbufferedDispatch` so it
  never disappears over the wallpaper (TASK-025 §4c machinery).
- Fix anything the on-device check exposes; no speculative rewrites.

## 3. Acceptance Criteria

| Criterion | Description | Status |
|---|---|---|
| **AC-01** | Task registered with architecture and AC table | **Done** |
| **AC-02** | `AlwKeyInterceptService` intercepts Super / Alt+Tab / Esc **only** while the session surface is focused; `onKeyEvent` returns `false` outside the app (system behaviour untouched) | **Code Complete** — service + `SessionInputBus` gate landed (`WaylandSurfaceView.isSessionFocused` from surfaceCreated+window focus, cleared on surfaceDestroyed/session stop/focus loss); on-device capture check pending (§6 step 5) |
| **AC-03** | Auto-heal: after the one-time ADB grant, `onResume` enables the service automatically (Shizuku path) or shows the copyable ADB command; verified on the Xiaomi Pad 8 | **Code Complete** — `AccessibilityHealer` (Shizuku → `WRITE_SECURE_SETTINGS` self-heal → copyable banner) wired into `MainActivity.onResume`; on-device grant/heal check pending |
| **AC-04** | Unit test asserts the complete keycode→evdev table (A–Z, 0–9, symbols, F1–F12, arrows, navigation, numpad, Ctrl/Alt/Super/Shift) | **Done** — `EvdevKeyMap` + 4 table tests + 2 fallback dispatch tests in `InputDispatcherTest`; 211 workspace tests green |
| **AC-05** | `nano`: Ctrl+X / Ctrl+C work; CachyOS SUPER binds (SUPER+Return/Q/M) fire from both the hardware and a11y paths | Planned |
| **AC-06** | Right/middle click and scroll axes verified in-guest; `wl_touch`/`wl_pointer` separation unchanged | Planned |
| **AC-07** | GAMING_RAW relative capture works; the cursor sprite never disappears over the wallpaper at high refresh | Planned |
| **AC-08** | No double key delivery between `MainActivity.dispatchKeyEvent` and `WaylandSurfaceView.onKeyDown` (unit test + on-device log) | Planned |
| **AC-09** | Unit tests + `./gradlew assembleDebug` green; evidence in `docs/tasks/TASK-027/evidence/`; commit pushed to `origin/main` | **Done** — 211 tests / 0 failures, `assembleDebug` green, [phase1-4-implementation.md](evidence/phase1-4-implementation.md) |

## 4. Verification Plan

1. Grant + heal: run the ADB grant, `onResume`, confirm the service is enabled; screenshot the
   banner path without the grant.
2. Keys: in the Hyprland session, SUPER+Return/Q/M, Alt+Tab, Esc, nano Ctrl+X/Ctrl+C; capture
   the guest-side result (`wev` / compositor log).
3. Table: unit test over the full map; spot-check F5/Home/PgDn/Num+ in `showkey`.
4. Pointer: right/middle click + wheel in a guest app; GAMING_RAW relative motion; cursor over
   the wallpaper at 120/144 Hz.
5. Scope: leave the app (Home), press Super/Back — confirm Android handles them (no swallow).
6. Regression: ungranted service state → hardware-keyboard path unchanged.

## 5. Risks & Mitigations

- **HyperOS may drop the a11y service from `enabled_accessibility_services`** on reboot/update
  → auto-heal runs on every `onResume`; banner fallback always available.
- **Shizuku settings-write API variance** → verify against the installed shizuku-api version
  first; the ADB path does not depend on Shizuku.
- **Interception scope bugs (swallowing system keys)** → `isSessionFocused` defaults to
  `false`; the service no-ops without the flag; verified in step 5.
- **Double key delivery** (activity + view both dispatch) → `dispatchKeyEvent` consumes when
  handled; add the unit test before touching dispatch order.

---

## 6. Phases 1–4 implementation record (2026-11-19)

Full record: [phase1-4-implementation.md](evidence/phase1-4-implementation.md). Build: 211 unit
tests / 0 failures, `./gradlew test assembleDebug` green.

**Phase 1 — session-scoped interception.** `SessionInputBus`
(`bridge/wayland/.../input/SessionInputBus.kt`) is the single gate: `WaylandSurfaceView` publishes
`isSessionFocused = surfaceActive && hasWindowFocus` from `surfaceCreated` / `onWindowFocusChanged`
/ `surfaceDestroyed`, and registers its `InputDispatcher` as the process-wide key sink on
`onAttachedToWindow` (unregistered on detach). `AlwKeyInterceptService`
(`app/.../service/AlwKeyInterceptService.kt`, `AccessibilityService` +
`flagRequestFilterKeyEvents` via `res/xml/alw_key_intercept_config.xml`) returns `false`
immediately unless the bus reports focus, then forwards into `InputDispatcher.handleKeyEvent`
(the hardware path) and consumes. `KEYCODE_BACK` maps to Esc (evdev 1) only through the focused
path. `DashboardViewModel.stopSession()` additionally clears the bus flag, so session stop can
never leave the interceptor latched.

**Phase 2 — auto-heal.** `AccessibilityHealer` runs on every `MainActivity.onResume`, in order:
(1) already enabled → no-op; (2) Shizuku companion `READY` → `settings put secure
enabled_accessibility_services` + `accessibility_enabled 1` through the UID 2000 shell
(`DefaultShizukuShellRunner`), re-verified afterwards; (3) app holds `WRITE_SECURE_SETTINGS`
(one-time `adb shell pm grant` — manifest declares it) → writes `Settings.Secure` directly;
(4) otherwise a dismissible Compose banner shows the one-time grant command with a Copy button
that also carries the full manual `settings put` block. The merge logic
(`parseEnabledServices` / `isOurServiceEntry` / `mergeServiceEntry`) is pure string handling —
both stored spellings (`pkg/pkg.Cls`, `pkg/.Cls`) are recognised, pre-existing services are
preserved byte-for-byte and never duplicated — and is unit-tested on the host JVM
(`AccessibilityHealerTest`, no Robolectric dependency).

**Phase 3 — complete evdev table.** `InputDispatcher.mapKeyCodeToScanCode` became
`EvdevKeyMap.KEYCODE_TO_EVDEV` (`bridge/wayland/.../input/EvdevKeyMap.kt`), an explicit
`Map<Int, Int>` covering the previous entries plus F1–F12 (59–68, 87, 88), MoveHome 102 /
MoveEnd 107 / PgUp 104 / PgDn 109 / Insert 110, SysRq 99 / ScrollLock 70 / Pause 119, the full
numpad block (0–9, dot, enter, +−×÷, NumLock 69), ISO 102nd 86 (`KEYCODE_POUND` — the UK-ISO
102nd # key; hardware ISO keyboards still deliver scanCode 86 as ground truth) and Menu→Compose
127. Hardware `scanCode > 0` still wins; the CapsLock→Super rebind is untouched. Tests:
`InputDispatcherTest.testEvdevTableCovers*` + two end-to-end fallback dispatch tests.

**Phase 4 — XKB `Mod4` sync & stuck-modifier hygiene.** New
`WaylandServerManager.clearKeyboardModifiers()` → JNI `nativeClearKeyboardModifiers` →
`WaylandSurfaceServer::clearKeyboardModifiers()` (`wayland_bridge.cpp`): zeroes
`m_depressedModifiers` (Shift 0x1 / Ctrl 0x4 / Alt 0x8 / Super 0x40), preserves the CapsLock
locked state, and re-broadcasts the change-deduped `wl_keyboard.modifiers`. Fired on every
focus-loss transition in `WaylandSurfaceView.updateSessionFocus()` (which also releases
GAMING_RAW pointer capture) and from `stopSession()`. Right-side modifiers were verified against
Android's generic meta bits (either side sets the generic bit) — no split change needed.

**Incidental lifecycle hygiene** (the "spawned-but-never-closed" audit from 2026-11-19):
`DashboardViewModel.observeShizukuState` now collects on `sessionScope`; the audio
`coordinateWith` collector is cancelled before relaunch; new `destroy()` cancels the scope from
`MainActivity.onDestroy` (a running session is intentionally left alive for Activity
recreations); `RiceHostBridge.sysfsLoop` sleeps in 250 ms slices so `stop()` joins promptly;
native threads are named (`alw-wayland-event`, `alw-wayland-pacing`, `alw-audio-loop`) via
`pthread_setname_np` so future `ps -T`/ANR traces are readable.

**Outstanding for Phases 5–6 (on-device):** SUPER+Return/Q/M + Alt+Tab + Esc capture in the
guest (`wev`), nano Ctrl+X/Ctrl+C, heal grant on the Pad 8, pointer/scroll/cursor + GAMING_RAW
capture checks, and the no-swallow scope check (Home → Super must stay Android).
