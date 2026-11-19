# TASK-034 — External USB-C DisplayPort Multi-Monitor Output (`wl_output` #2)

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Multi-Output Wayland Presentation, Display Hotplug & Input Routing  
**Board:** [Central task board](../../../TASKS.md) is the workflow-status source.  
**Owner / harness:** Antigravity / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Write scope:** `bridge/wayland/`, `app/`, `docs/tasks/TASK-034/`  
**Risk:** High  
**Status:** **Planned** — spec registered; implementation starts after TASK-026/027/028 are complete.  
**Depends on:** TASK-026 (host presenter, `wl_output.mode` re-broadcast, render scale), TASK-027 (input bridge and pointer/touch routing), TASK-024 (Wayland bridge).  
**Fallback preserved:** with no external display the bridge continues to present exactly one `wl_output` (`WL-1`) on the internal panel; every multi-output code path is skipped. If a secondary surface cannot be attached, the internal session is unaffected.

---

## 1. Problem Statement

The bridge is single-output. `WaylandSurfaceView` owns one `Surface`, `WaylandServerManager`
forwards one `Surface` to `nativeSetSurface()`, and `wayland_bridge.cpp` holds one
`m_nativeWindow`, one `m_width`/`m_height`, and announces one `wl_output` (`WL-1`) bound to
`client.outputId`. `sendSurfaceEnterIfNeeded()` always enters that single output, and the commit
handler blits every main surface to the single window.

An external USB-C DisplayPort monitor therefore has no representation:

| Area | Current state | Gap |
|---|---|---|
| Detection | None | No `DisplayManager` / `Presentation` handling for external displays |
| Native window | One `ANativeWindow` | No second window / second BufferQueue |
| Outputs | One `wl_output`, name `WL-1` | No `DP-1`, no per-output geometry/mode |
| Surface association | Every surface enters the one output | No "which output does this surface belong to" |
| Presentation | One blit target | A second output's surface would be drawn to the internal panel |
| Input | One view, one coordinate space | No display-local → virtual-desktop mapping or focus routing |
| Hotplug | None | No add/remove handling, no `wl_registry.global_remove` |

## 2. Architecture — two `wl_output`s

```
┌──────────── Android app ─────────────────────────────────────────────────────┐
│  MainActivity / SessionSurfaceScreen                                          │
│    DisplayManager.getDisplays() → external HDMI/DP display (if any)           │
│    Presentation(context, externalDisplay) {                                    │
│        WaylandSurfaceView #2 (or detached SurfaceView)                         │
│    }                                                                          │
│    internal WaylandSurfaceView #1                                             │
│         │ attachSurface(surface, outputId)       │ attachSurface(surface, 1)   │
└─────────┼────────────────────────────────────────┼────────────────────────────┘
          ▼                                        ▼
┌──────────────────────── wayland_bridge.cpp ──────────────────────────────────┐
│  OutputRegistry                                                               │
│    [0] WL-1  internal  ANativeWindow@primary  geometry (0,0)  mode 3200x2136  │
│    [1] DP-1  external  ANativeWindow@presentation geometry (W,0) mode dynamic  │
│                                                                               │
│  per client: multiple wl_output bindings                                      │
│    wl_output(x=0):  geometry · mode · scale · name("WL-1") · description · done│
│    wl_output(x=1):  geometry · mode · scale · name("DP-1") · description · done│
│  wl_surface.enter(output) per surface → ClientSurface.outputId                 │
│  commit → present to the ANativeWindow of ClientSurface.outputId               │
│  hotplug → wl_registry.global (add) / global_remove + layout re-broadcast      │
└──────────────┬────────────────────────────────────────────────────────────────┘
               │ input (per display-local view)
┌──────────────▼────────────────────────────────────────────────────────────────┐
│  InputDispatcher: local (x,y) + output origin → virtual-desktop (x,y)          │
│    wl_pointer absolute position across the combined desktop                    │
│    wl_touch coordinates in virtual-desktop space                               │
│    focus follows the display under the pointer / last touch                    │
└───────────────────────────────────────────────────────────────────────────────┘
```

### 2.1 Native output registry

- Replace the single `m_nativeWindow`/`m_width`/`m_height` state with an `OutputState`
  (`id`, `name`, `ANativeWindow*`, `width`, `height`, `refreshHz`, `originX`, `originY`,
  `geometryMm`, `scale`) and a fixed primary output at index 0.
- `WaylandClient` must support **multiple** bound `wl_output` ids (today `client.outputId` is a
  single value). Keep a map `outputId → client object id`.
- Announce each output with `geometry(x, y, …)`, `mode(width, height, refresh_mHz)`, `scale`,
  `name`, `description`, `done`. A non-`0,0` geometry origin is how the nested compositor learns
  the monitor layout.
- `sendSurfaceEnterIfNeeded()` must send `wl_surface.enter` for the output(s) the surface belongs
  to; a surface has exactly one `ClientSurface.outputId`, defaulting to the primary.
- On unplug: emit `wl_registry.global_remove` for the `wl_output` object, force surfaces that
  entered it back to the primary, and drop the `ANativeWindow`.
- On plug: create the output, announce it, and (if the guest compositor supports it) re-broadcast
  layout/mode so it picks the new output up without a restart.

### 2.2 Presentation

- Each output owns an `ANativeWindow` and an independent EGL presenter (or a shared EGL context
  with a per-window `EGLSurface`). Do **not** reuse one EGLSurface across two windows; the
  TASK-026 Phase 1 lesson about `setBuffersGeometry` invalidating a live swapchain applies.
- Per-output `setBuffersGeometry`/render-scale: the external display may run at a different
  resolution and refresh rate. Keep the internal panel's render scale independent.
- Commit routing: the frame from a surface is presented to the window of that surface's output.
  A fullscreen internal scene must never be blitted onto the external monitor and vice versa.
- `wl_output.mode` + `done` on external display mode/refresh change; re-configure affected
  `xdg_toplevel`s only where necessary.

### 2.3 Input routing

- Each `SurfaceView` reports display-local coordinates. `InputDispatcher` adds the output origin
  before producing `wl_pointer`/`wl_touch` coordinates in the combined virtual desktop.
- Stylus/tablet-v2 coordinates (TASK-030) use the same transform, so pressure/tilt strokes land
  on the correct monitor.
- Focus: pointer enter/leave and touch on the external display update the active output; the
  compositor's focus follows. Avoid duplicate events when the same pointer crosses displays.
- Use the fixed layout (`WL-1` at `0,0`; `DP-1` at `WL-1.width,0`) unless the guest compositor
  reports a different arrangement; keep the layout deterministic and documented.

## 3. Phases

### Phase 0 — Detection and a second surface
- `DisplayManager` + `Presentation` plumbing in `app/`; create a second `Surface` when an
  external display is present.
- New JNI `nativeSetSurfaceForOutput(outputId, surface)` + `WaylandServerManager` API (mockable
  for JVM tests).

### Phase 1 — Native multi-output registry
- `OutputState` registry, per-client multiple `wl_output` names, geometry/mode/scale/name/desc/done
  announcements for `WL-1` and `DP-1`.
- Per-output `ANativeWindow` and EGL presenter teardown/rebuild on attach/detach.

### Phase 2 — Surface association and correct presentation
- Track `wl_surface.enter` per output; store `ClientSurface.outputId`; route commits to the right
  window.
- Verify a window moved to `DP-1` renders there and not on the internal panel.

### Phase 3 — Input routing and focus
- Output-origin transform for pointer/touch/stylus; focus/output switching across displays.
- Verify no duplicate or dropped events at the boundary.

### Phase 4 — Hotplug, dynamic mode and refresh
- `global_remove` / global add, surface fallback to primary, layout re-broadcast.
- External resolution/refresh change via `wl_output.mode`; independent render scale.

### Phase 5 — Tests and evidence
- Unit tests for the output registry, coordinate transform, and global add/remove bookkeeping;
  on-device evidence with a real USB-C monitor (screenshots, `wayland-info`).

## 4. Acceptance Criteria

| Criterion | Description | Status |
|---|---|---|
| **AC-01** | Task registered with architecture, phases and this AC table | **Done** |
| **AC-02** | With a USB-C/DP monitor attached, `wayland-info` (and `wlr-randr`) lists two outputs: `WL-1` and `DP-1` with correct modes | Planned |
| **AC-03** | The desktop spans both displays; a window moved to `DP-1` renders on that monitor and not on the internal panel (screenshot) | Planned |
| **AC-04** | Pointer, touch and stylus coordinates are correct on both displays; focus follows the active display | Planned |
| **AC-05** | Unplug/replug recovers without restarting the session: output removed, layout updated, surfaces fall back to `WL-1` | Planned |
| **AC-06** | External resolution/refresh changes are reflected via `wl_output.mode`; render scale is independent per output | Planned |
| **AC-07** | With no external display the single-output path is byte-for-byte the current behaviour (regression) | Planned |
| **AC-08** | Unit tests + `./gradlew assembleDebug` green; evidence in `docs/tasks/TASK-034/evidence/`; commit pushed to `origin/main` | Planned |

## 5. Verification Plan

1. Attach a USB-C DP monitor; `wayland-info`/`wlr-randr` output list and modes.
2. Arrange monitors in the guest; screenshot both displays.
3. Move a window to `DP-1`; verify content and no mirroring artefacts.
4. Input: pointer across the boundary, touch on each display, stylus on the external display.
5. Hotplug: unplug and replug while a window is on `DP-1`; verify recovery and no crash.
6. Mode: change the external resolution/refresh; verify the guest reports the new mode.
7. Regression: detach the monitor; confirm the internal session is unchanged.

## 6. Risks & Mitigations

- **Multiple EGLSurfaces sharing one context** → one window/one surface; never present a surface
  to the wrong `ANativeWindow`; tear down per-output presenters on attach/detach.
- **Compositor layout assumptions** → advertise explicit `wl_output.geometry` origins and verify
  against Hyprland's monitor selection; keep a deterministic default layout.
- **Input coordinate drift** → central transform helper + unit tests; verify at the boundary.
- **Hotplug races in the native event loop** → serialize output add/remove on the event-loop
  thread; never touch an `ANativeWindow` that a client still references.
- **Presentation API availability** → fall back to no external output when the app cannot obtain
  a presentation display; the internal session must never be blocked.
- **`hyprctl` unavailable under PRoot** (TASK-024) → verify with `wlr-randr`/`wayland-info` and
  screenshots instead of Hyprland IPC.
