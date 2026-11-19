# TASK-028 — Customizable On-Screen Virtual Keyboard (V-KB)

**Epic:** [EPIC-ALW-P3 — Phase 3](../../initiatives/android-linux-workstation/epics/phase-3-onboarding-ui-integrations/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Jetpack Compose UI, Input Injection & Persistent Preferences  
**Board:** [Central task board](../../../TASKS.md) is the workflow-status source.  
**Owner / harness:** Antigravity / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Write scope:** `app/`, `docs/tasks/TASK-028/`  
**Risk:** Medium  
**Status:** **Planned** — spec registered; implementation starts after TASK-027.  
**Depends on:** TASK-027 (evdev/XKB injector, sticky-modifier state, session-focus gate).  
**Fallback preserved:** the physical-keyboard and touch-to-pointer paths are untouched; the overlay is hidden by default and every behaviour is opt-in.

---

## 1. Problem Statement

Touch-first use of the Hyprland desktop lacks the modifier/terminal keys a desktop session
assumes: no `Ctrl`, `Alt`, `Super`, `Esc`, `Tab`, arrows or function keys on the tablet's
software input. An on-screen virtual keyboard wired **directly** into the TASK-027
evdev/XKB injector (not the Android IME) gives deterministic keysyms, sticky modifiers and
customizable layouts without stealing input focus from the session surface.

## 2. Architecture

```
┌──────────────────── Android app (session screen) ────────────────────────────┐
│  FloatingControlPill  [ALW · PEN · 2.1ms · 0.75x · CTRL · ⌨]                 │
│                                                        │ toggle (hidden by   │
│                                                        │ default, user rule) │
│  VirtualKeyboardOverlay (Compose Popup, bottom-anchored, NON-focusable)      │
│    layouts: Terminal Strip · Full 60% · Custom JSON (files/vkb_layouts/)     │
│    sliders: opacity 15–95% · scale 0.7x–1.4x (live preview)                  │
│    sticky modifiers: CTRL · ALT · SUPER · SHIFT (one-shot / latch)           │
│         │  key tap = sendKey(scanCode, isDown, metaState ⊕ stickyMods)       │
│         ▼                                                                    │
│  InputDispatcher → WaylandServerManager.sendKey → nativeSendKeyEvent         │
│    (same evdev/XKB path as hardware keys — TASK-027 state machine)           │
└───────────────────────────────────────────────────────────────────────────────┘
```

### 2.1 Files

- `app/src/main/java/com/alw/workstation/ui/session/vkb/VirtualKeyboardOverlay.kt` — overlay
  composables (key grid, modifier row, sliders).
- `app/src/main/java/com/alw/workstation/ui/session/vkb/VkbModel.kt` — layout model, sticky
  modifier state, persistence keys.
- `app/src/main/java/com/alw/workstation/ui/session/vkb/VkbLayoutParser.kt` — JSON layout
  loader/validator.
- `app/src/main/assets/vkb_layouts/{terminal_strip.json,full_60.json}` — shipped samples,
  copied to `files/vkb_layouts/` on first run (never overwrite user files).

### 2.2 Layout JSON schema

```json
{
  "name": "terminal-strip",
  "rows": [
    [ {"label": "ESC", "keycode": 111}, {"label": "TAB", "keycode": 61, "width": 1.5},
      {"label": "CTRL", "keycode": 129, "sticky": true},
      {"label": "ALT", "keycode": 57, "sticky": true},
      {"label": "SUPER", "keycode": 399, "sticky": true},
      {"label": "↑", "keycode": 19, "repeat": true}, {"label": "⏎", "keycode": 66} ]
  ]
}
```

`keycode` is the Android `KEYCODE_*` value (the dispatcher maps it through the TASK-027 evdev
table). Parser rules: unknown keycodes are skipped with a log line (never crash), `width`
defaults to 1.0, `sticky`/`repeat` default to false; a file with zero valid keys is rejected.

### 2.3 Interaction rules

- The overlay is **non-focusable** so `WaylandSurfaceView` keeps input focus and the guest
  keeps receiving events while the overlay is visible.
- Sticky modifiers: tap arms the modifier for the next non-modifier key (one-shot); long-press
  latches/unlatches (CTRL latch stays consistent with the TASK-026/027 HUD CTRL pill — one
  shared state); visual active state; cleared on focus loss.
- Key repeat: press-and-hold repeats using `ViewConfiguration` repeat timeouts — no magic
  constants (project taste).
- Sliders apply live; values persist in SharedPreferences
  (`vkb.opacity` 15–95, `vkb.scale` 0.7–1.4, `vkb.layout`, `vkb.visible=false`).

## 3. Acceptance Criteria

| Criterion | Description | Status |
|---|---|---|
| **AC-01** | Task registered with architecture and AC table | **Done** |
| **AC-02** | The HUD ⌨ toggle opens/closes the overlay; the guest keeps receiving keys while it is visible (surface focus never stolen) | Planned |
| **AC-03** | Terminal Strip and Full 60% layouts render; taps produce the correct keysyms in-guest (verified via `wev`/`showkey`/kitty) | Planned |
| **AC-04** | Custom JSON loads from `files/vkb_layouts/`; a malformed file is rejected without crashing | Planned |
| **AC-05** | Opacity (15–95%) and scale (0.7×–1.4×) sliders apply live and persist across app restarts | Planned |
| **AC-06** | Sticky modifiers compose: SUPER then T opens the guest terminal; CTRL then X sends ^X in nano | Planned |
| **AC-07** | Parser + metaState-composition unit tests green; `./gradlew assembleDebug` green; evidence in `docs/tasks/TASK-028/evidence/`; commit pushed to `origin/main` | Planned |

## 4. Verification Plan

1. Toggle the overlay from the HUD; type into the guest with the strip and the 60% layout;
   capture `wev`/kitty output.
2. Load a custom JSON; corrupt one on purpose (bad keycode, zero rows) — expect skip/reject
   logs, no crash.
3. Move both sliders; rotate the device; restart the app — values persist.
4. Sticky sequences: SUPER+T (Hyprland terminal), CTRL+X in nano, SHIFT+arrows in less.
5. Focus checks: keyboard input keeps flowing to the guest while the overlay is open; closing
   the overlay restores the pre-overlay state.

## 5. Risks & Mitigations

- **Focus theft from the SurfaceView** → non-focusable Popup; verified on-device in step 5.
- **Overlay taps leaking to the guest desktop** → bounds-only hit area (the overlay consumes
  touches inside its bounds only).
- **Recomposition cost at 120 Hz** → static keys via `remember`, sliders isolated to leaf
  composables.
- **Custom layouts referencing unknown keycodes** → parser skips + logs (AC-04), never crashes.
