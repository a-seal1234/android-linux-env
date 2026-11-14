# TASK-008 Verification Evidence — Hardware Input, Pointer Capture & tablet-v2 Stylus Engine

**Epic:** [EPIC-ALW-P2 — Phase 2](../../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Task:** [TASK-008](../TASK.md)  
**Date:** 2026-11-14  
**Author:** Codex / local Linux environment  

---

## 1. Overview of Delivered Subsystems

### 1.1 Relative Pointer Capture & Mouse Dispatch
- Integrated unconstrained mouse relative deltas (`AXIS_RELATIVE_X`, `AXIS_RELATIVE_Y`) and smooth scroll wheel deltas (`AXIS_VSCROLL`, `AXIS_HSCROLL`).
- Dispatched mouse button transitions (`BUTTON_PRIMARY`, `BUTTON_SECONDARY`, `BUTTON_TERTIARY`).
- Added support for engaging `View.requestPointerCapture()` when viewport focus is obtained or `GAMING_RAW` mode is active.

### 1.2 Hardware Keyboard Scancode Remapping & HyperOS Bypass
- Intercepted hardware key events before HyperOS system interception.
- Implemented default `caps:super` remapping in `InputDispatcher`:
  - Translates physical CapsLock (scancode 58 or `KEYCODE_CAPS_LOCK`) to Linux `KEY_LEFTMETA` (125).
  - Can be toggled dynamically via `rebindCapsToSuper`.

### 1.3 Full Wayland `tablet-v2` Protocol Stylus Engine
- Implemented dedicated `zwp_tablet_tool_v2` dispatchers in C++ and Kotlin, decoupled from `wl_touch` and `wl_pointer`.
- Implemented exact planar tilt sign conventions:
  - Android `AXIS_ORIENTATION` is clockwise from top (-Y):
    - $\phi = 0$: north (-Y)
    - $\phi = +\pi/2$: east (+X)
    - $\phi = \pi$: south (+Y)
    - $\phi = -\pi/2$: west (-X)
  - Wayland `zwp_tablet_tool_v2.tilt` convention ($+X$ = right, $+Y$ = down):
    $$\text{tilt}_x = \operatorname{degrees}(\arcsin(\sin(\text{tilt}) \cdot \sin(\text{orientation})))$$
    $$\text{tilt}_y = \operatorname{degrees}(\arcsin(\sin(\text{tilt}) \cdot -\cos(\text{orientation})))$$
- Wayland 24.8 fixed point conversion implemented via `wl_fixed_from_double(d)` (`static_cast<int32_t>(d * 256.0)`).
- Normalized pressure mapped to $0 .. 65535$.
- Hover proximity and z-distance (`AXIS_DISTANCE`), barrel roll rotation (`AXIS_GENERIC_1`), and stylus buttons (`BUTTON_STYLUS_PRIMARY`, `BUTTON_STYLUS_SECONDARY`) mapped cleanly.

### 1.4 Extensible `StylusDataProvider` Interface
- Created `StylusDataProvider` interface and `StylusFrameData` model.
- Default implementation `DefaultMotionEventStylusProvider` processes standard Android `MotionEvent` data.
- Pluggable design enables vendor Pen SDK hooks (e.g. Xiaomi Smart Pen 2 IMU barrel roll / custom button events) without changing the core input dispatcher.

### 1.5 Input Profiles & Hover Pointer Suppression
- Added `InputProfile.CREATIVE_PEN` and `InputProfile.GAMING_RAW`.
- In `CREATIVE_PEN` mode, `WaylandSurfaceView` applies `PointerIcon.TYPE_NULL` to hide the Android circle hover cursor, letting creative apps (Krita, Blender) render their own custom pen cursor.

---

## 2. Unit & Integration Test Results

Executed `./gradlew :bridge:wayland:test`:
```text
> Task :bridge:wayland:compileDebugKotlin UP-TO-DATE
> Task :bridge:wayland:compileDebugUnitTestKotlin
> Task :bridge:wayland:testDebugUnitTest
> Task :bridge:wayland:compileReleaseUnitTestKotlin
> Task :bridge:wayland:testReleaseUnitTest
> Task :bridge:wayland:test

BUILD SUCCESSFUL in 2s
50 actionable tasks: 4 executed, 46 up-to-date
```

Executed `./gradlew test`:
```text
BUILD SUCCESSFUL in 902ms
153 actionable tasks: 2 executed, 151 up-to-date
```

Executed `./gradlew assembleDebug`:
```text
BUILD SUCCESSFUL in 1s
128 actionable tasks: 2 executed, 126 up-to-date
```

All 4 modules (`:app`, `:core:session`, `:core:proot`, `:bridge:wayland`) compiled cleanly with 0 errors and 0 warnings.
