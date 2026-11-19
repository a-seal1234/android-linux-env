# TASK-026 Phase 1 — Host GLES2/3 blit diagnosis & FPS pill (evidence)

Device: Xiaomi Pad 8 (`25097RP43G`, Android 16 / HyperOS, Adreno 825), wireless ADB
`192.168.51.161:44021`. Captured 2026-11-19.

## Root cause of the "swaps but nothing appears" symptom

Five defects in the presenter path, all fixed in `bridge/wayland/src/main/cpp/wayland_bridge.cpp`:

1. **Stale `EGLSurface` across window replacement.** `setNativeWindow()` released the
   `ANativeWindow` without tearing down the presenter, and `ensureGlPresenter()` short-circuits on
   `m_glReady`. The EGL surface stayed bound to a destroyed BufferQueue, so every
   `eglSwapBuffers()` reported success while the consumer saw nothing. `setNativeWindow()` now
   calls `destroyGlPresenter()` before swapping windows.
2. **`ANativeWindow_setBuffersGeometry()` under a live EGLSurface.** `resizeSurface()` (driven by
   `surfaceChanged` → `attachSurface` → `resizeSurface`) reallocated the BufferQueue behind the
   EGL swapchain. It now tears the presenter down and drops the cursor shadow before re-applying
   geometry.
3. **`ANativeWindow_lock()` racing the EGL swapchain.** `drawCursorOverlay()` (cursor motion) and
   the CPU blit both locked the window that EGL owned. Both paths now bail out while
   `m_glReady` is true.
4. **Vertical flip.** The fullscreen quad mapped source row 0 (`v = 0`, the guest's top row) to
   NDC `y = -1` (window bottom), presenting the desktop upside down. `v` is now inverted.
5. **Unreliable upload format.** The upload used `GL_BGRA_EXT`, an optional extension. It now
   uploads core `GL_RGBA` and the fragment shader swizzles `.bgr`, which is correct for
   little-endian ARGB8888/XRGB8888 (bytes B,G,R,A).

Additional hardening: the EGL config's `EGL_NATIVE_VISUAL_ID` is now logged against the
`WINDOW_FORMAT_RGBA_8888` window format, and a periodic `glReadPixels` probe (sampled before
`eglSwapBuffers`, while the drawn buffer is still the back buffer) reports the presented centre
pixel plus `eglQuerySurface` dimensions.

## Presentation path is now GPU by default

`ALW_GLES_BLIT=0` or the `debug.alw.gles_blit` system property `0` forces the CPU path; anything
else uses EGL/GLES with an automatic per-frame fallback to the CPU blit if the presenter cannot
initialize or swap.

GPU path (default) on-device:

```
GLES: EGL config 0xb4000079225b09e0 native visual 0x1 (window format 0x1, match=1)
GLES presenter ready (Adreno (TM) 825, es3=1)
GLES probe: surface=2400x1602 reported=2400x1602 center=0xff513e3a
GLES blit #601: 2400x1602 (scale 0.75, fps 2.9)
```

`center=0xff513e3a` (non-black) is the direct proof that the composed frame reaches the surface;
[`gpu_blit_hyprland_desktop.png`](gpu_blit_hyprland_desktop.png) shows the full Sekai Pop desktop
(wallpaper, kitty + fastfetch, Quickshell bar/dock, FPS pill) rendered through it.

CPU fallback (`debug.alw.gles_blit=0`) renders the identical desktop — no EGL presenter is
initialized, only `ANativeWindow_lock`:
[`cpu_fallback_hyprland_desktop.png`](cpu_fallback_hyprland_desktop.png),
[`cpu_fallback_path.log`](cpu_fallback_path.log).

## Live FPS telemetry

`alw-host-query fps` inside the guest:

```
[root@localhost ~]# alw-host-query fps
fps=1 res=2400x1602 scale=0.75 gpu=zink-adreno825
```

The Quickshell `alw-fps` pill shows the same value (`1–3 FPS · 0.75x`) bottom-right. `AlwFpsShellGenerator`
now deploys `shell.qml` into both guest homes; the on-device copy predates the generator and was
correctly left untouched (non-destructive).

## Frame rate: measured, and not the presenter

Both presentation paths sit at ~2 FPS on this session (GPU: `GLES blit ... fps 1.9–2.9`; CPU:
`144Hz Frame Pacing ... FPS: 1.7–3.0`). Since the CPU path never touches EGL, the ceiling is the
**guest compositor**: Hyprland renders on `kms_swrast` (CPU) at 2400×1602 with blur enabled. The
host blit is not the bottleneck; making Hyprland itself GPU-rendered is Phase 3b (time-boxed
compositor-zink spike).

## Notes / residual issues

- `dmabuf: AHardwareBuffer_createFromHandle rejected` still appears; the linear `mmap` path feeds
  the GLES upload, so presentation is unaffected (TASK-024 §6.2 / TASK-025 note 5).
- Cursor sprite: with the EGL presenter owning the window the CPU overlay is skipped (it would
  fight the swapchain); the guest compositor draws the cursor itself
  (`cursor:no_hardware_cursors = 1`), which is what the screenshots show.
