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

---

## 4. Audio Routing via PipeWire / PulseAudio

The workstation creates a low-latency native audio bridge:
1. The bridge exposes a standard PulseAudio-compatible UNIX domain socket at `$XDG_RUNTIME_DIR/pulse/native`.
2. Inside the rootfs, PipeWire's `pipewire-pulse` daemon connects directly to this socket.
3. The native bridge reads 16-bit PCM stereo / 48kHz audio frames and feeds them directly into an Android `AAudio` or `Oboe` low-latency stream.
4. Measured audio latency target: < 30ms round-trip.
