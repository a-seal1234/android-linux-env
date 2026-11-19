# TASK-030 — Full Wayland Tablet Protocol (`zwp_tablet_v2`) for Xiaomi Smart Pen

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Wayland Input Protocol, Stylus Pipeline & Creative-App Integration  
**Board:** [Central task board](../../../TASKS.md) is the workflow-status source.  
**Owner / harness:** Antigravity / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Write scope:** `bridge/wayland/`, `app/`, `docs/tasks/TASK-030/`  
**Risk:** High  
**Status:** **Planned** — spec registered; implementation starts after TASK-026/027/028 are complete.  
**Depends on:** TASK-027 (input bridge, `evdev`/XKB path, session-focus gate), TASK-008 (`tablet-v2` engine, `XiaomiPenStylusProvider`, `StylusTiltMath`), TASK-026 Phase 1.  
**Fallback preserved:** finger input keeps using `wl_touch`, and the existing `wl_pointer` emulation for non-tablet-aware clients stays available. When no tablet tool is advertised or a client ignores `zwp_tablet_v2`, the pen still drives the pointer path.

---

## 1. Problem Statement

`zwp_tablet_manager_v2` is announced as global 5 and `zwp_tablet_tool_v2` dispatch helpers exist,
but no protocol objects are ever created and no events are emitted. In
`bridge/wayland/src/main/cpp/wayland_bridge.cpp` the `sendTablet*` methods only mutate a private
`TabletToolState`; `InputDispatcher.kt` (TASK-008) therefore reaches Krita/Xournal++/Inkscape as a
pressure-less mouse. Concretely:

| Area | Current state | Gap |
|---|---|---|
| Manager | `zwp_tablet_manager_v2` bound to `client.tabletManagerId`, no request handling | `get_tablet_seat()` never called |
| Seat/tablet/tool objects | None created | No `tablet_added` / `tool_added`, no `done`/`capability` sequence |
| Motion | `sendTabletMotion()` stores state only | No `motion`, `pressure`, `distance`, `tilt`, `rotation` events |
| Proximity | `sendTabletProximity()` stores a bool | No `proximity_in(serial, tablet, surface, x, y)` / `proximity_out` |
| Tip | `sendTabletDown/Up()` store state | No `down` / `up` events; no tip serial |
| Buttons | `sendTabletButton(bit, isDown)` receives an Android bit (`1 << i`) | No `button(serial, BTN_STYLUS/BTN_STYLUS2, state)` |
| Frame | None | Events are not coalesced into a `frame(time)` batch |
| Input routing | `InputDispatcher.dispatchStylusEvent()` also calls `sendPointerAbsolute/Button` | Pen drives both tablet and pointer unconditionally; hover enter/exit not modelled |

## 2. Architecture — complete tablet-v2 pipeline

```
Android MotionEvent (TOOL_TYPE_STYLUS / ERASER)
        │  XiaomiPenStylusProvider + StylusTiltMath (altitude + azimuth, pressure curve)
        ▼
InputDispatcher.dispatchStylusEvent()
        │  one logical frame per MotionEvent batch
        ▼
WaylandServerManager (JNI)
        │
        ▼
wayland_bridge.cpp  tablet-v2 object graph
  zwp_tablet_manager_v2 ──get_tablet_seat──► zwp_tablet_seat_v2
        │                                          │
        │                            tablet_added ─┴─ tool_added
        │                                 │              │
        │                          zwp_tablet_v2   zwp_tablet_tool_v2
        │                          name/id/path     type/hardware_serial/
        │                          done/removed     hardware_id_wacom/capability/
        │                                           done · proximity_in/out ·
        │                                           down/up · motion · pressure ·
        │                                           distance · tilt · rotation ·
        │                                           button · frame
        ▼
Guest compositor (Hyprland) → Krita / Xournal++ / Inkscape
```

### 2.1 Mapping table

| Android / provider value | Wayland tablet-v2 event | Notes |
|---|---|---|
| `TOOL_TYPE_STYLUS` / `ERASER` | `tool.type` (pen / eraser) | Emit once per tool creation, before `done` |
| `AXIS_PRESSURE` (0..1) | `pressure` (0..65535) | Clamp; eraser pressure respected |
| `AXIS_TILT` (rad from normal) + `AXIS_ORIENTATION` (azimuth) | `tilt(tilt_x, tilt_y)` wl_fixed degrees | Project via `StylusTiltMath`; +X right, +Y down |
| Barrel roll / IMU twist (`XiaomiPenStylusProvider.rotationDegrees`) | `rotation(degrees)` | `AXIS_ORIENTATION` is azimuth, **not** twist: do not send it as rotation |
| Z-height hover (`StylusDataProvider.distance`) | `distance` wl_fixed | Only when the tool capability is set |
| Hover `ACTION_HOVER_ENTER/EXIT` | `proximity_in` / `proximity_out` | `proximity_in` carries tablet + surface + local x/y |
| Tip `ACTION_DOWN/UP` | `down` / `up` | Serial per transition |
| Barrel buttons | `button(serial, 331=BTN_STYLUS, 332=BTN_STYLUS2, state)` | Replace the Android bit mask with evdev codes |
| One MotionEvent batch | one `frame(time_ms)` | Never emit a lone motion without a frame |

### 2.2 Object lifecycle rules

- `wl_registry.bind(zwp_tablet_manager_v2)` → on request `get_tablet_seat(new_id, seat)` create a
  `zwp_tablet_seat_v2` and immediately emit `tablet_added(tablet_id)` then `tool_added(tool_id)`
  (protocol order), each followed by its `name`/`id`/`path`/`type`/`capability` events and `done`.
- Tool `removed`/tablet `removed` on client teardown or when the pen leaves the digitizer for
  good; do not leak object ids.
- `proximity_in` needs the current client `activeSurfaceId` and surface-local coordinates; if no
  surface is active the event is dropped (never sent with an unbound surface).
- Pressures and coordinates are `wl_fixed`; distances and tilt degrees use the 24.8 format.
- Frame coalescing: buffer the events produced by one `handleMotionEvent()` call and flush once.

## 3. Phases

### Phase 0 — Protocol object plumbing
- Add `tabletSeatIds`, `tabletIds`, `tabletToolIds` to `WaylandClient`; handle
  `zwp_tablet_manager_v2.get_tablet_seat` and its `destroy`.
- Implement `tablet_added` / `tool_added` with `name`, `id`, `path`, `type`, `hardware_serial`,
  `hardware_id_wacom`, `capability`, `done`.
- Unit-testable object/id bookkeeping in the Kotlin JNI seam (mock harness).

### Phase 1 — Motion, pressure, tilt, proximity
- Replace state-only setters with event emitters; compute `tilt_x/tilt_y` from the provider.
- Implement `proximity_in/out` for hover transitions and `motion` for hover moves.
- Emit `down`/`up` with serials; keep pressure at 0 on `up`.
- Ensure guest-visible coordinates match the surface (letterbox/render-scale aware).

### Phase 2 — Buttons, eraser, frame coalescing
- Map the provider button mask to `BTN_STYLUS` (331) / `BTN_STYLUS2` (332); emit
  `button(serial, code, state)` only on transitions.
- Earser tip flips `tool.type` to eraser and zeroes tip pressure semantics.
- Coalesce each input batch into one `frame(time_ms)`.

### Phase 3 — InputDispatcher integration and non-interference
- Model explicit hover enter/exit in `dispatchStylusEvent()`.
- Send pointer emulation only when the client lacks tablet-v2 (`isTabletV2Supported()`), or keep
  it as a documented fallback controlled by the input profile; never synthesize `wl_touch` for a
  stylus.
- Verify finger `TOOL_TYPE_FINGER` still maps to `wl_touch` only and multi-touch is unchanged.

### Phase 4 — On-device creative-app verification
- Krita: pressure, tilt, eraser, barrel button modifiers.
- Xournal++: pen/eraser and pressure.
- Inkscape: pressure/tilt and hover cursor.
- `wayland-info`/`wev` capture showing the tablet tool event stream.

## 4. Acceptance Criteria

| Criterion | Description | Status |
|---|---|---|
| **AC-01** | Task registered with architecture, mapping table and this AC table | **Done** |
| **AC-02** | `wayland-info`/`wev` shows `zwp_tablet_v2` objects with `tablet_added` + `tool_added` and a complete `capability`/`done` sequence | Planned |
| **AC-03** | `pressure` reaches the guest in `0..65535` and tracks the pen; tip `down`/`up` are serialized correctly | Planned |
| **AC-04** | `tilt_x`/`tilt_y` (degrees) follow pen altitude/azimuth with the documented sign convention; `rotation` is only emitted from a real twist source | Planned |
| **AC-05** | Hover generates `proximity_in` / `proximity_out` with the active surface and local coordinates | Planned |
| **AC-06** | Barrel buttons emit `BTN_STYLUS` (331) / `BTN_STYLUS2` (332) on transitions only | Planned |
| **AC-07** | Krita, Xournal++ and Inkscape respond to pressure/tilt/erased as expected (screenshots in evidence) | Planned |
| **AC-08** | Finger input still arrives as `wl_touch`; the stylus never synthesizes touch; pointer emulation is gated and documented | Planned |
| **AC-09** | Unit tests for the mapping/state machine + `./gradlew assembleDebug` green; evidence in `docs/tasks/TASK-030/evidence/`; commit pushed to `origin/main` | Planned |

## 5. Verification Plan

1. Protocol: run `wayland-info`/`wev` inside the guest; capture the tablet tool event stream.
2. Pressure/tilt: draw a slow gradient stroke in Krita; inspect the stroke profile; screenshot.
3. Hover: hold the pen at 5–10 mm; confirm `proximity_in` and the cursor state; lift → `proximity_out`.
4. Buttons: bind barrel buttons in Krita/Xournal++; verify events and no duplicates.
5. Eraser: flip the pen; confirm eraser strokes in Xournal++/Krita.
6. Non-interference: two-finger touch scrolling in a browser and touch-only UI still work; no
   `wl_touch` events appear in the stylus capture.

## 6. Risks & Mitigations

- **Protocol ordering (tablet before tool, `done` last)** → enforce in the emitter; validate with
  `wayland-info`, which aborts on malformed sequences.
- **Azimuth mistaken for twist** → only emit `rotation` from an explicit provider source; document
  the mapping table.
- **Duplicate input (tablet + pointer)** → gate pointer emulation on tablet support / profile;
  add a unit test asserting no `wl_touch` from stylus.
- **Coordinate mismatch after render-scale changes** → reuse the bridge's surface-coordinate
  transform and verify at 0.5× and 1.0×.
- **Client compat (Krita/Xournal++ versions)** → verify against the provisioned versions and
  record the exact versions in evidence.
