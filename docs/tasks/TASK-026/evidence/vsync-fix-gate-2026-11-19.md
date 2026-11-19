# GPU Blocker Gate — VSync-fix measurement (2026-11-19, after `alw-vsync-fix.apk`)

Device: Xiaomi Pad 8 (`25097RP43G`, Android 16 / HyperOS, Adreno 825), wireless ADB
`192.168.51.161:44021`. Build installed 22:56 (30,594,768 bytes, `lastUpdateTime` verified).
Compositor: **zink** (`MESA_LOADER_DRIVER_OVERRIDE=zink`, `GALLIUM_DRIVER=zink`,
`ALW_COMPOSITOR_ZINK=1`, `forceCompositorZink=true`).

## Verdict: **GATE NOT PASSED**

Peak sustained active-movement rate measured **28.7 FPS** (0.5× scale) and **26.4 FPS**
(1.0×). The requirement is **>= 30 FPS sustained**, so TASK-027 / TASK-028 stay **Blocked**.
Progress is large but the bar is not met.

## 1. The fix that mattered: missing VSync driver

The bridge had `onVsync()` in C++ but **no JNI binding and no Kotlin caller** — the frame pacer
had no presentation clock and fell back to its 250 ms dead-man timer. That is why the earlier
numbers were ~3.8 FPS at *both* 0.75× and 0.40× scale: the ceiling was the timer, not the GPU.

After wiring `nativeOnVsync` + a `Choreographer.FrameCallback` loop in `WaylandSurfaceView`
(re-armed per frame, stopped on `surfaceDestroyed`):

```
VSync tick #0    (frameTime=915280952578868 ns)
VSync tick #1    (frameTime=915280969245627 ns)   ← 16.7 ms apart = 60 Hz
VSync tick #300  (frameTime=915284852687355 ns)
```

The pacer now runs at the real display cadence.

## 2. Host blit is no longer the bottleneck

Per-stage timing (`glFinish()` forced after the upload so the number is real):

| Scale | upload | draw+swap | host total |
|---|---|---|---|
| 1600×1068 (0.50×) | 0.72–1.13 ms | 0.60–2.14 ms | **1.36–3.32 ms** |
| 2400×1602 (0.75×) | 1.33–2.90 ms | 0.97–2.20 ms | **2.67–4.60 ms** |
| 3200×2136 (1.00×) | 2.32–2.90 ms | 0.97–2.20 ms | **3.72–4.60 ms** |

The whole host-side present costs ~2–5 ms, i.e. it could sustain 200+ FPS on its own. The
`mmap` fallback costs a similar 1.5–3.5 ms (`map=0.08–0.17ms blit=1.42–3.38ms`).

## 3. Measured production rates (sustained window movement)

| Condition | Frame commits | FPS |
|---|---|---|
| 0.5×, 18 s movement (measurement 1) | 518 | **28.7** |
| 0.5×, 20 s movement (measurement 2) | 502 | 25.1 |
| 0.5×, 16 s movement (measurement 3) | 442 | 27.6 |
| 1.0×, 18 s movement | 483 | 26.8 |
| 1.0×, 20 s coupled CPU/GPU sample | 529 | 26.4 |
| Guest-side animation only (no adb input) | 148 / 12 s | 12.3 |

Steady-state frame interval during guest animation: **45–52 ms** with occasional
250–440 ms hitches (allocation / swapchain rebuild).

## 4. Coupled CPU / GPU sample (`sample-gate.sh`)

```
23:14:41 cpu_app=27.5 cpu_hypr=31.0 cpu_proot=72.4 load1=7.88 gpuMB(app/hypr)=502/238 tz0=52000
23:14:44 cpu_app=19.2 cpu_hypr=19.2 cpu_proot=34.6 load1=7.88 gpuMB(app/hypr)=502/238 tz0=57600
23:14:43 cpu_app=27.5 cpu_hypr=31.0 cpu_proot=51.7 load1=7.88 gpuMB(app/hypr)=502/238 tz0=55600
```

- Hyprland on zink: **19–34 %** CPU (was **341 %** on `kms_swrast` — a ~10× improvement).
- PRoot tracer: 34–72 % — PRoot syscall translation is now a first-order cost.
- GPU memory held by the app: ~500 MB (framebuffers + swapchain + textures).
- Thermal zone 0 (AOSS): 51–58 °C.

**KGSL GPU utilisation/frequency is not readable**: `/sys/class/kgsl/kgsl-3d0/{gpu_busy_percentage,
gpuclk,devfreq/cur_freq}` return `Permission denied` for the adb shell *and* for the app UID.
`dumpsys gpu` exposes per-process GPU memory only (`Global total`, `Proc <pid> total`), not
load/clock — so GPU utilisation could not be paired with FPS as requested; the metrics above are
the closest obtainable set without root.

## 5. Measurement caveat found

`adb shell input swipe` drives input through `system_server`, which measured **74–78 %** CPU
during those runs and competes with the guest for cores. The FPS numbers obtained by adb-driven
swipes are therefore **lower bounds**; the guest-only animation run (12.3 FPS of *terminal
scrolling*, not window movement) is not comparable. Peak real window-movement figure remains
**28.7 FPS**.

## 6. Remaining gap analysis (why < 30)

With host present at ~2–5 ms and CPU idle at ~465/800 % during load, the limit is **serialised
per-frame latency**, not throughput:

1. **Zero-copy still absent** — every frame still crosses the CPU
   (`mmap fallback blit active … map=0.14ms blit=1.93ms`). `EGL_EXT_image_dma_buf_import` is
   **not advertised** by this Adreno driver (`ext_image_dma_buf_import=0`) and
   `AHardwareBuffer_createFromHandle` is rejected (`rc=-1`), plus guest rows are 64-byte padded
   (stride 9728) which `AHardwareBuffer_Desc` cannot describe. Closing this needs a different
   transport (e.g. gralloc-independent import or a shared-memory path), not just a flag.
2. **PRoot syscall translation** consumes 34–72 % CPU per frame.
3. **Hitch frames** (250–440 ms) from allocation/swapchain churn cost the average.

## 7. What *is* proven

- VSync pacing fix: real display cadence instead of a 250 ms timer (**3.8 → 28.7 FPS**, ~7.5×).
- Compositor-zink works on-device and cuts compositor CPU ~10× (341 % → ~19–34 %).
- Host GPU blit is fast: **upload ~1 ms, draw+swap ~1 ms** at 1600×1068; ~2.4 ms / ~1.4 ms at
  full 3200×2136.
- llvmpipe relief (blur + shadow disabled) is applied in the live session config.

Evidence: [`vsync-fix-measurement.log`](vsync-fix-measurement.log),
[`sample-gate.sh`](sample-gate.sh).
