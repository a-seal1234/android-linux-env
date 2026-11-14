# Wayland Native Bridge Design Specification

**Status:** Approved Specification (Phase 0)  
**Document:** `docs/architecture/WAYLAND-BRIDGE-DESIGN.md`  
**Reference Implementations:** `Beauty114514/trierarch-app`, `wlroots`, `wayland-server`  

---

## 1. Overview & Architecture

The `wayland-bridge` is a native C/Rust component embedded directly into the Android application process. Rather than launching an external display server (such as an X11 server or VNC daemon) or writing a `libwayland-server` + `ANativeWindow` compositor from scratch, the bridge vendors and adapts the proven embedded Wayland compositor and PulseAudio/PipeWire bridge architecture from **`Beauty114514/trierarch-app`**.

The adapted architecture embeds an in-process Wayland display server (`wl_display`) that renders directly into an Android `ANativeWindow` while forwarding guest audio through a dedicated PulseAudio-compatible UNIX domain socket.

```
+--------------------------------------------------------------------------+
|                     Android Application (JVM / UI)                       |
|   SurfaceView.getHolder().addCallback() -> Surface Created (ANativeWindow) |
+-----------------------------------+--------------------------------------+
                                    | JNI: onSurfaceCreated(ANativeWindow*)
                                    v
+--------------------------------------------------------------------------+
|              wayland-bridge (Adapted from trierarch-app)                 |
|                                                                          |
|   +---------------------+   +---------------------+   +----------------+ |
|   | Wayland Sockets Mgr |   |  Buffer Compositor  |   |  Input Router  | |
|   | ($XDG_RUNTIME_DIR/  |   |  (wl_shm / dmabuf   |   | (Pointer Capt, | |
|   |  wayland-0)         |   |   -> ANativeWindow) |   |  Scancode Map) | |
|   +----------+----------+   +----------+----------+   +--------+-------+ |
+--------------|-------------------------|-----------------------|---------+
               | Wayland Protocol        | Direct Render         | IPC / FD
               v                         v                       v
+--------------------------------------------------------------------------+
|                     Guest Linux Session (PRoot)                          |
|   Compositor (Hyprland / LabWC) -> Client Apps (Chromium, Antigravity)    |
+--------------------------------------------------------------------------+
```

---

## 2. Surface & Buffer Lifecycle

### 2.1 Surface Acquisition via JNI
When the Android Compose viewport instantiates a `SurfaceView`:
1. `SurfaceHolder.Callback.surfaceCreated(holder)` acquires the native window via `ANativeWindow_fromSurface(env, surface)`.
2. `ANativeWindow_setBuffersGeometry(window, width, height, WINDOW_FORMAT_RGBA_8888)` configures the presentation pipeline to match the physical tablet panel (e.g. 3200 x 2136 @ 144Hz).
3. The native handle is retained in the native Wayland renderer context.
4. When `surfaceDestroyed` occurs, active rendering is locked and queued buffers are safely retired before releasing the `ANativeWindow` reference.

### 2.2 Wayland Buffer Presentation
The bridge implements:
- `wl_shm`: Supports `WL_SHM_FORMAT_XRGB8888` and `WL_SHM_FORMAT_ARGB8888`. Buffers backed by memfd/ashmem are copied or blitted directly to the locked `ANativeWindow_Buffer`.
- `linux_dmabuf_v1`: Facilitates hardware zero-copy buffer sharing when Turnip exports DRM PRIME buffers compatible with Android `AHardwareBuffer`.
- Double/Triple Buffering: Synchronized with the Android Choreographer display refresh loop (up to 144Hz on Xiaomi Pad 8) to prevent tearing and micro-stuttering.

---

## 3. Hardware Input Architecture

### 3.1 Relative Pointer Capture & Hardware Mouse
Standard Android touch and pointer handling clamps mouse coordinates to screen boundaries, rendering traditional first-person camera or desktop window panning unusable.

1. When the `SurfaceView` gains focus, `session-core` invokes `View.requestPointerCapture()`.
2. Relative mouse coordinates (`MotionEvent.AXIS_RELATIVE_X`, `MotionEvent.AXIS_RELATIVE_Y`) are routed directly via JNI into the native input dispatcher.
3. The bridge translates these deltas into standard Wayland `wl_pointer.motion` events.
4. Smooth scroll wheel actions are mapped to `wl_pointer.axis` and `wl_pointer.axis_discrete`.
5. Primary, secondary, and tertiary mouse buttons map directly to Linux evdev button codes (`BTN_LEFT`, `BTN_RIGHT`, `BTN_MIDDLE`).

### 3.2 Hardware Keyboard Mapping & HyperOS Interception Bypass
On Xiaomi HyperOS, pressing the physical `Super` (Windows) key triggers the system launcher or quick search, stealing focus from the workstation desktop.

To counteract this:
1. The workstation bridge provides two-layer protection:
   - **Layer A (Software scancode translation):** Remaps `KEYCODE_CAPS_LOCK` to `KEY_LEFTMETA` (`Super`) within the guest session via default `kb_options = caps:super`.
   - **Layer B (JNI Raw Key Interception):** Overrides `onKeyDown` / `onKeyUp` in the workstation `Activity`, consuming the raw hardware scancode before the HyperOS window manager intercepts it.

### 3.3 Stylus & Graphics Tablet Architecture (`tablet-v2` Protocol)
For creative workflows in desktop Linux applications such as **Krita**, **Blender** (Grease Pencil & Sculpting), **Inkscape**, and **GIMP**, routing stylus events through standard `wl_pointer` or `wl_touch` degrades the user experience by stripping pressure sensitivity, tilt, and button states, or causing unwanted touch panning/pinching.

The `wayland-bridge` implements a dedicated Wayland `tablet-v2` protocol layer (`zwp_tablet_manager_v2`, `zwp_tablet_seat_v2`, `zwp_tablet_v2`, and `zwp_tablet_tool_v2`) strictly decoupled from touch and pointer dispatchers:

```
+--------------------------------------------------------------------------+
|                       Android Stylus Input Layer                         |
|   MotionEvent (TOOL_TYPE_STYLUS / ERASER) OR Extensible Vendor Pen Hook  |
+-----------------------------------+--------------------------------------+
                                    | JNI: dispatchStylusEvent()
                                    v
+--------------------------------------------------------------------------+
|           wayland-bridge Native Input Router (tablet-v2 Engine)          |
|                                                                          |
|   +-----------------------+  +-------------------+  +------------------+ |
|   |  Coordinate & Range   |  | Planar Tilt Calc  |  | State Dispatcher | |
|   |  x, y -> [0..surface] |  | tilt_x, tilt_y    |  | proximity, motion| |
|   |  pressure -> [0..1.0] |  | (-90 to +90 deg)  |  | down, up, button | |
|   +-----------------------+  +-------------------+  +------------------+ |
+-----------------------------------|--------------------------------------+
                                    | Wayland zwp_tablet_tool_v2 events
                                    v
+--------------------------------------------------------------------------+
|                     Guest Linux Session (PRoot)                          |
|         Wayland Compositor -> Native ARM64 Krita / Blender               |
+--------------------------------------------------------------------------+
```

1. **Protocol Implementation:**
   - Exposes `zwp_tablet_manager_v2` global (version 1).
   - Manages virtual tablet seat devices (`zwp_tablet_v2`) representing the integrated tablet digitizer.
   - Dynamically binds pen tools (`zwp_tablet_tool_v2`) with types `PEN`, `ERASER`, and `BRUSH`.
2. **Android `MotionEvent` Coordinate & Axis Mapping:**
   - **Position:** `x`, `y` scaled to the virtual desktop surface.
   - **Pressure:** Normalized from Android `event.getPressure()` to `[0.0, 1.0]` (emitted via `zwp_tablet_tool_v2.pressure` with range 65535).
   - **Planar Tilt Calculation:** Wayland requires planar tilt angles ($tilt_x$, $tilt_y$) in degrees $[-90^\circ, +90^\circ]$. Android reports altitude tilt ($\theta = \text{AXIS\_TILT} \in [0, \pi/2]$) and azimuth orientation ($\phi = \text{AXIS\_ORIENTATION} \in [-\pi, \pi]$). The bridge mathematically projects these spherical angles:
     $$\text{tilt}_x = \operatorname{degrees}\left(\arcsin\left(\sin(\text{AXIS\_TILT}) \cdot -\sin(\text{AXIS\_ORIENTATION})\right)\right)$$
     $$\text{tilt}_y = \operatorname{degrees}\left(\arcsin\left(\sin(\text{AXIS\_TILT}) \cdot \cos(\text{AXIS\_ORIENTATION})\right)\right)$$
   - **Hover & Proximity:** `ACTION_HOVER_MOVE` and `ACTION_HOVER_ENTER` dispatch `zwp_tablet_tool_v2.proximity_in` followed by `zwp_tablet_tool_v2.motion`. `ACTION_HOVER_EXIT` dispatches `zwp_tablet_tool_v2.proximity_out`.
   - **Distance:** `MotionEvent.getAxisValue(AXIS_DISTANCE)` mapped to `zwp_tablet_tool_v2.distance` (range 65535).
   - **Barrel Roll / Rotation:** `MotionEvent.getAxisValue(AXIS_GENERIC_1)` or device orientation mapped to `zwp_tablet_tool_v2.rotation` ($0^\circ$ to $360^\circ$).
   - **Buttons:**
     - Pen tip down/up: `zwp_tablet_tool_v2.down` / `up`.
     - Stylus primary/barrel button (`BUTTON_STYLUS_PRIMARY`): mapped to `zwp_tablet_tool_v2.button(BTN_STYLUS, state)`.
     - Stylus secondary button (`BUTTON_STYLUS_SECONDARY`): mapped to `zwp_tablet_tool_v2.button(BTN_STYLUS2, state)`.
     - Eraser tool type (`TOOL_TYPE_ERASER`): activates `zwp_tablet_tool_v2` with `TYPE_ERASER`.
3. **Extensible `StylusDataProvider` Interface:**
   - Defined in Kotlin and JNI to allow raw high-precision pressure curves, sub-millimeter hover Z-distance, barrel roll IMU vectors, and hardware shortcut buttons from vendor Pen SDK hooks (e.g. Xiaomi Smart Pen 2 SDK or custom HID reader) to override or augment standard Android `MotionEvent` data without changing bridge core code.
4. **Input Profile Switching & System Hover Icon Suppression:**
   - **`CREATIVE_PEN` Profile:** Routes stylus events exclusively to `tablet-v2`, suppresses system mouse hover icons by applying `View.setPointerIcon(PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL))` over the viewport, and enables 144Hz/240Hz pen sampling.
   - **`GAMING_RAW` Profile:** Enforces relative hardware mouse capture, locks system cursor, and routes mouse deltas directly to `wl_pointer`.

---

## 4. Audio Routing via PipeWire / PulseAudio

The workstation creates a low-latency native audio bridge:
1. The bridge exposes a standard PulseAudio-compatible UNIX domain socket at `$XDG_RUNTIME_DIR/pulse/native`.
2. Inside the rootfs, PipeWire's `pipewire-pulse` daemon connects directly to this socket.
3. The native bridge reads 16-bit PCM stereo / 48kHz audio frames and feeds them directly into an Android `AAudio` or `Oboe` low-latency stream.
4. Measured audio latency target: < 30ms round-trip.
