# freedreno (KGSL) on-device validation — 2026-11-21

**Device:** Xiaomi Pad 8 (`25097RP43G`), Android 16, app `com.alw.workstation` 0.1.0,
wireless ADB `192.168.51.161:45527`.
**Build:** `./gradlew :app:assembleDebug` from the working tree (exit 0), `adb install -r -d` → `Success`.
**Session profile:** `arch-xiaomi-pad-8` → `compositor=hyprland`, `gpu.driverMode=freedreno_kgsl`.

## Result

freedreno renders the full desktop directly on the Adreno 825 at native resolution
(`GLES blit #61: 3200x2136 (scale 1.00, fps 22.9)`, user-observed 60 FPS while animating)
**with no visual artifacts**, once freedreno is forced into linear (sysmem) rendering.

## Root cause of the "heavy visual artifact"

Artifacts appear as regular vertical bands of small dashes over large gradients (wallpaper),
i.e. the classic signature of macro-tile data read as if it were linear.

1. The host presenter cannot import guest dma-bufs zero-copy: `dmabuf: probe image_base=1
   native_buffer=1 native_client_buffer=1 ext_image_dma_buf_import=0 -> zero-copy (AHB)`.
   `EGL_EXT_image_dma_buf_import` is absent on this Adreno EGL, and
   `AHardwareBuffer_createFromHandle` rejects the raw `dma_heap` handle produced by the
   virtual DRM node, so every frame goes through
   `dmabuf: mmap fallback blit active (3200x2136 stride=12800 fmt=0x34325258)`.
2. That fallback reads the buffer **linearly** (`presentWithGl()`), so the guest render
   target must be linear. freedreno defaults to tiled/UBWC output for a6xx, so the tile
   layout was presented as diagonal/vertical shear.
3. `FD_MESA_DEBUG=notile,noubwc` did **not** help: both flags are documented as
   "for all **internal** buffers", and the compositor's scanout buffer is an imported
   dma-buf, not an internal resource.

## Fix

`FD_MESA_DEBUG=sysmem,noubwc` (freedreno's "Force sysmem rendering" +
"Disable UBWC for all internal buffers") makes every guest surface linear, which is what
the bridge's upload path expects. It is now emitted by:

- `EnvironmentGenerator` (`DriverMode.FREEDRENO_KGSL`) — whole session, and
- `RiceShimGenerator.ALW_GUI_RUN_SHIM` (`freedreno)` branch) — every `alw-gui-run` app,
  respecting an inherited `FD_MESA_DEBUG`.

Unit tests: `EnvironmentGeneratorTest`, `RiceShimGeneratorTest` assert the value.
Gates: `./gradlew :core:session:test :core:proot:test :app:assembleDebug` → BUILD SUCCESSFUL.

## Evidence trail

| Step | Observation |
|---|---|
| Baseline freedreno | stripes everywhere — [2026-11-21-freedreno-tiled-artifacts.png](2026-11-21-freedreno-tiled-artifacts.png) |
| `FD_MESA_DEBUG=notile,noubwc` | stripes unchanged (internal-buffer-only flags) |
| `FD_MESA_DEBUG=sysmem,noubwc` | clean frame — [2026-11-21-freedreno-sysmem-clean.png](2026-11-21-freedreno-sysmem-clean.png) |
| Final APK, env **not** in the profile | `FD_MESA_DEBUG=sysmem,noubwc` present in `/proc/<Hyprland>/environ` from code; frame still clean |

Supporting environment proof from the same session:

```
ALW_GL_BACKEND=freedreno
MESA_LOADER_DRIVER_OVERRIDE=kgsl
GALLIUM_DRIVER=kgsl
FD_MESA_DEBUG=sysmem,noubwc
# Hyprland holds fds 28/35/143 -> /dev/kgsl-3d0, and libgallium-27.0.0-arch1.92.so is mapped
```

## Still open

- Presentation remains the CPU `mmap` + `glTexSubImage2D` path (`upload 2–7 ms`,
  `draw+swap 0.5–1.7 ms` at 3200x2136). True zero-copy needs gralloc-backed guest buffers
  (AHB allocated by the app, handed to the guest through the virtual DRM node) so the host
  can import them by pointer instead of `createFromHandle`.
- With render scale now defaulting to 1.0 the synthesized monitor scale `1.75` is rejected
  by Hyprland's nested output (`Invalid scale passed to monitor WAYLAND-1: 1.75, using
  rounded scale: 1.60`); the surface still presents correctly at 3200x2136.
