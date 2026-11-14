# TASK-008 — Pointer Capture, Keyboard Scancode Mapping & tablet-v2 Stylus Engine

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Implementation / Hardware Input & Stylus Digitizer Engine  
**Board:** [Central task board](../../../TASKS.md) is the only workflow-status source.  
**Owner / harness:** Codex / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Risk:** High Risk (Relative pointer capture, HyperOS key interception, high-precision stylus math)  
**Source:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md) (REQ-WAYLAND-003, REQ-WAYLAND-004)

---

## 1. Outcome

Implement the comprehensive desktop and creative input subsystem in `bridge:wayland`:
1. **Relative Pointer Capture & Desktop Mouse Dispatch:**
   - Enforces `View.requestPointerCapture()` on the workstation viewport.
   - Routes unconstrained relative deltas (`AXIS_RELATIVE_X`, `AXIS_RELATIVE_Y`) to native `wl_pointer.motion`.
   - Dispatches smooth wheel scrolling (`wl_pointer.axis`, `axis_discrete`) and mouse buttons (`BTN_LEFT`, `BTN_RIGHT`, `BTN_MIDDLE`).
2. **Hardware Keyboard Scancode Mapping & HyperOS Super Key Bypass:**
   - Captures raw key scancodes via JNI before the HyperOS window manager intercepts `Super` (Windows) keys.
   - Injects default `kb_options = caps:super` translating `Caps Lock` to `Super` inside guest compositors (Hyprland / LabWC).
3. **Full Wayland `tablet-v2` Stylus Input Engine:**
   - Dedicated `zwp_tablet_tool_v2` dispatching layer strictly separated from `wl_touch` and `wl_pointer` so creative tools (Krita, Blender) recognize the display as a true pen display.
   - Maps Android `MotionEvent` stylus data:
     - **Position & Pressure:** `x`, `y` coordinates and normalized pressure (0–65535).
     - **Planar Tilt Projection:** Projects spherical `AXIS_TILT` (altitude) and `AXIS_ORIENTATION` (azimuth) into planar `tilt_x` and `tilt_y` in degrees $[-90^\circ, +90^\circ]$:
       $$\text{tilt}_x = \operatorname{degrees}(\arcsin(\sin(\text{AXIS\_TILT}) \cdot -\sin(\text{AXIS\_ORIENTATION})))$$
       $$\text{tilt}_y = \operatorname{degrees}(\arcsin(\sin(\text{AXIS\_TILT}) \cdot \cos(\text{AXIS\_ORIENTATION})))$$
     - **Hover & Proximity:** `ACTION_HOVER_MOVE` / `ACTION_HOVER_ENTER` -> `proximity_in` + `motion`; `ACTION_HOVER_EXIT` -> `proximity_out`.
     - **Hover Z-Distance:** `AXIS_DISTANCE` mapped to `zwp_tablet_tool_v2.distance`.
     - **Barrel Roll:** `AXIS_GENERIC_1` / `rotation` mapped to `zwp_tablet_tool_v2.rotation` ($0^\circ$ to $360^\circ$).
     - **Stylus Buttons:** `BUTTON_STYLUS_PRIMARY` -> `BTN_STYLUS`, `BUTTON_STYLUS_SECONDARY` -> `BTN_STYLUS2`, `TOOL_TYPE_ERASER` -> `ERASER`.
4. **Extensible `StylusDataProvider` Interface:**
   - Pluggable Kotlin/JNI provider allowing custom vendor Pen SDK hooks (e.g. Xiaomi Smart Pen 2 SDK or custom HID driver) to override or augment raw pressure, hover Z-distance, IMU barrel roll, and button events.
5. **Input Profile Switching & System Hover Icon Suppression:**
   - **`CREATIVE_PEN` Profile:** Routes pen events exclusively to `tablet-v2`, suppresses system hover pointer via `PointerIcon.TYPE_NULL`, and optimizes pen sampling.
   - **`GAMING_RAW` Profile:** Enforces relative pointer capture and 1000Hz raw mouse updates.

---

## 2. Planned Scope & Acceptance Criteria

- **AC-1 (Pointer Capture):** `requestPointerCapture()` engages upon viewport focus; relative mouse deltas stream cleanly without clamping to screen edges.
- **AC-2 (Keyboard Scancode Mapping):** Hardware keyboard scancodes forward into guest XKB state; `caps:super` translation verified.
- **AC-3 (Stylus `tablet-v2` Events):** Stylus events dispatch through `zwp_tablet_tool_v2` with pressure, planar tilt ($tilt_x$, $tilt_y$), distance, and button states; touch and pointer channels remain unaffected.
- **AC-4 (Hover Icon Suppression):** Hovering stylus over Wayland surface suppresses the default Android circle cursor via `PointerIcon.TYPE_NULL`.
- **AC-5 (Extensible Pen Hook):** `StylusDataProvider` interface successfully delivers augmented vendor pen telemetry into the JNI bridge.
- **AC-6 (Unit & Integration Verification):** Unit tests verify planar tilt mathematical projection, input profile switching, and scancode translation.

---

## 3. Dependencies & Gates

- **Prerequisites:** TASK-007 (Completed).
- **Implementation Gate:** Passed. Full tablet-v2, pointer capture, scancode remapping, and `StylusDataProvider` implemented.
- **Verification Gate:** Passed. All unit tests (`StylusTiltMathTest`, `DefaultMotionEventStylusProviderTest`, `InputDispatcherTest`) and `./gradlew assembleDebug` passed.
- **Review Gate:** Passed. Evidence recorded in [evidence/verification.md](evidence/verification.md).
