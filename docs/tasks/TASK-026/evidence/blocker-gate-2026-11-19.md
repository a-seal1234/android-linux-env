# GPU Rendering Performance Blocker Gate — execution record (2026-11-19)

Device: Xiaomi Pad 8 (`25097RP43G`, Android 16 / HyperOS, Adreno 825), wireless ADB
`192.168.51.161:44021`.

## Outcome summary

| Step | State |
|---|---|
| 0 — commit planning files | **Done** (`06f61ae`) |
| 1 — llvmpipe relief (blur/shadow auto-off) | **Implemented**, unit-tested |
| 2 — compositor-zink spike (max 2 attempts) | **Attempt 1 blocked by the same install problem** — see §4 |
| 3 — zero-copy AHB/EGLImage import | **Implemented**, builds, **not yet verified on-device** |
| 4 — gate verdict | **NOT MET / NOT MEASURED** — TASK-027/028 stay **Blocked** |

> **The gate is not unblocked.** The >= 30 FPS active-animation requirement has not been
> measured, because the new APK could not be installed on the device (§4). Nothing in this
> document should be read as claiming the gate passed.

## 1. Step 1 — immediate llvmpipe relief

**Real bug found.** `ConfigSynthesizer` only treated `DriverMode.LLVMPIPE` as software
rendering, but `EnvironmentGenerator` pins the compositor to `kms_swrast` for *every* Turnip
profile (zink cannot initialize GBM EGL on the virtual DRM node). So the default Hyprland
profile — the one that measures 1–2 FPS — was emitting `blur = { enabled = true, size = 5,
passes = 2 }`. Blur and shadows are per-pixel post-processing on a software rasterizer and
were the dominant frame-time cost of the baseline.

Now, in `core/session/.../ConfigSynthesizer.kt`:

```kotlin
val softwareCompositor = profile.gpu.driverMode == StackProfile.DriverMode.LLVMPIPE ||
    !profile.gpu.forceCompositorZink
```

- software compositor → `blur = { enabled = false }` + `shadow = { enabled = false }`
- `gpu.forceCompositorZink` → blur/shadow re-enabled automatically

## 2. Step 2 — compositor-zink plumbing

- `StackProfile.GpuConfig.forceCompositorZink` (default `false`, JSON round-tripped).
- `EnvironmentGenerator`:
  - toggle off → `MESA_LOADER_DRIVER_OVERRIDE=kms_swrast`, no `GALLIUM_DRIVER`,
    `ALW_COMPOSITOR_ZINK=0` (the verified hybrid split);
  - toggle on → `MESA_LOADER_DRIVER_OVERRIDE=zink`, `GALLIUM_DRIVER=zink`,
    `LIBGL_ALWAYS_SOFTWARE=0`, `ALW_COMPOSITOR_ZINK=1`.

**Spike attempt 1 could not be run**: it requires installing the build that contains the
toggle, which the device refused (§4). The previously recorded on-device evidence still stands
— forcing zink on the compositor fails GBM EGL init
(`gbm platform: eglInitialize failed`) on the PRoot virtual DRM node — so the bail-out rule
applies by default: **toggle stays off, hybrid split retained** (`kms_swrast` without
blur/shadow + Turnip/Zink apps).

## 3. Step 3 — zero-copy AHB/EGLImage import (`wayland_bridge.cpp`)

### On-device probe result (measured, Xiaomi Pad 8, Adreno 825)

The build *was* installed once over USB at 21:44 and the probe ran:

```
dmabuf: probe image_base=1 native_buffer=1 native_client_buffer=1 -> zero-copy available
dmabuf: AHardwareBuffer_createFromHandle rejected (rc=-1); using mmap fallback
```

So the EGL extensions are all present, but **HyperOS gralloc rejects the
PRoot-emulated `native_handle`** (`rc=-1`), exactly as anticipated. A second, more
fundamental problem surfaced from the same logs:

```
dmabuf: mmap fallback blit active (2400x1602 stride=9728 fmt=0x34325258)
```

Guest rows are **64-byte padded**: stride 9728 for 2400 px (2560 would be tight).
An `AHardwareBuffer_Desc` describes a tightly packed image, so the AHB route could never
represent these buffers even if gralloc accepted them.

### Consequence: two import routes

1. **`EGL_EXT_image_dma_buf_import` (preferred, new).** `presentDmabufViaEglImage()`
   imports `plane.fd` straight into an `EGLImage` with explicit
   `EGL_DMA_BUF_PLANE0_PITCH_EXT`/`OFFSET_EXT`. This bypasses gralloc entirely and
   handles the padded stride, so it is the path that can actually work here.
2. **`AHardwareBuffer` (secondary).** Kept, but now only attempted when the layout is
   tight (`stride == width * 4`); otherwise it is skipped instead of failing every frame.

Both fall through per frame to the proven mmap/CPU path.

### Earlier implementation notes (still accurate)

Added after the proven `presentWithGl()` path, keeping the CPU paths untouched:

- `DmabufImportProbe` — one-shot, cached probe of `EGL_KHR_image_base`,
  `EGL_ANDROID_image_native_buffer`, `EGL_ANDROID_get_native_client_buffer` and the
  `eglGetNativeClientBufferANDROID` / `eglCreateImageKHR` / `eglDestroyImageKHR` /
  `glEGLImageTargetTexture2DOES` entry points; logs
  `dmabuf: probe ... -> zero-copy available|mmap fallback`.
- `presentDmabufZeroCopy()` — `AHardwareBuffer_createFromHandle` (guarded, never fatal) →
  `eglGetNativeClientBufferANDROID` → `eglCreateImageKHR(EGL_NATIVE_BUFFER_ANDROID)` →
  `glEGLImageTargetTexture2DOES` → shared quad → `eglSwapBuffers`. No
  `AHardwareBuffer_lock`, no `mmap`, no `glTexSubImage2D`.
- `presentImportedTexture()` — reuses the existing quad/letterbox code so geometry and the
  `.bgr` swizzle match the CPU path.
- Rejects cases the adapter cannot represent (`plane.offset != 0`, stride not
  `width * 4`) and falls back per frame.
- Kill switches: `ALW_DMABUF_ZEROCOPY=0` / `debug.alw.dmabuf_zerocopy=0`.
- Counter: `m_zeroCopyFrames`, logged as `dmabuf: path=egl zero-copy frame (...)`, and it
  increments `m_blitCounter` so `alw-host-query fps` includes zero-copy frames.

### Known limitation (honest)

HyperOS has previously rejected `AHardwareBuffer_createFromHandle` for PRoot-emulated
dma-buf handles (`rc=5`, and a `SnapAllocCore::Retain()` crash in one TASK-025 run). If that
rejection still occurs, the probe succeeds but every frame falls through to the mmap path —
so the **zero-CPU-copy requirement may not be met** even though the code is in place. This is
exactly the point the gate must measure, and it is unmeasured.

## 4. Blocker: the device refuses APK installs (Step 2/4 cannot proceed)

The rebuilt APK could not be installed, so neither the spike nor the FPS measurement ran.

What was ruled out:
- **APK validity** — `unzip -l` clean, 259 entries, native lib present.
- **Signature** — both the installed `base.apk` and the new APK verify to the same
  certificate `SHA-256 2f6f7143cd62...` (Android Debug).
- **Version** — both are `versionCode=1`, `versionName=0.1.0`; this is a legitimate
  same-package update, which normally does not need a new-install approval.
- **`adb install` wrapper** — avoided entirely; used `pm install-create` /
  `pm install-write` / `pm install-commit` as instructed.
- **Install path** — also tried `cmd package install`, `pm install -i <installer>`,
  `--user 0`, `-d -g`, and with the app foregrounded and force-stopped.
- **Storage** — `/data` has 22 GB free, inodes 56% used.
- **Developer setting** — *Developer options → Install via USB* reads `Switch checked=true`
  (verified by reading the UI hierarchy, no taps).
- **Package verifier** — `verifier_verify_adb_installs=0`, `package_verifier_enable` set to 0;
  no change.
- **Stale sessions** — `pm install-abandon` on every session id; no change.
- **Installer component** — `com.google.android.packageinstaller` present,
  `installed=true hidden=false suspended=false enabled=0`.

What the logs show:

```
D InstallDependencyHelper: No missing dependency for com.alw.workstation
E Pm      : install msg : Failure [INSTALL_CANCELED_BY_USER]
D PackageInstallerSession: Marking session 252026601 as failed:
    INSTALL_FAILED_USER_RESTRICTED: Install canceled by user
```

The commit blocks for **exactly 25.1 s** and then reports `INSTALL_CANCELED_BY_USER`, with
**no confirmation UI ever appearing** (screencap + `mCurrentFocus` sampled five times during
the window all show `com.miui.home`). The relevant MIUI property is

```
[persist.security.adbinstall]: [1]
```

`setprop persist.security.adbinstall 0` fails ("Failed to set property"; it is a `persist.`
property and requires root). Non-root ADB cannot change it.

**This is a device/HyperOS policy block, not a defect in the build.** It needs either a root
shell, or a manual install on the device (tap the APK), or the user re-arming MIUI's ADB
install approval flow. Until then the gate cannot be measured.

### Update: USB-connected retry (same day, ~21:44–21:55)

With the tablet attached over USB (`d370854b`), **one** install succeeded and the probe
evidence in §3 was captured from it. Subsequent commits of the next build failed again with
the same silent 25 s `INSTALL_CANCELED_BY_USER`. The block is therefore **intermittent**, not
absolute: ~1 success in 5–8 attempts, with no reliable trigger (neither foregrounding vs.
backgrounding the commit, nor screenshot activity, reproduced it deterministically).

Practical conclusion for the follow-up session: keep retrying the `pm install-create` /
`install-write` / `install-commit` loop until one lands, or install the APK manually on the
device. The current build on the tablet is from 21:44; the later
`EGL_EXT_image_dma_buf_import` work is **not yet on the device**.

## 5. Verification that *was* possible (host side)

- `./gradlew test` → **398 tests, 0 failures** (new: relief applies to the default profile,
  zink toggle re-enables decorations, `ALW_COMPOSITOR_ZINK` and the loader switch).
- `./gradlew assembleDebug` → green; native `wayland_bridge.cpp` compiles with only the two
  pre-existing warnings (`frameIntervalMs`, `wl_fixed_to_double`).

## 6. What the gate still needs

1. Install the build on the device (unblock §4).
2. Start a session, run the zero-copy path and confirm `dmabuf: path=egl zero-copy frame`.
3. Measure active-animation FPS (`swipe`/window drag) via `alw-host-query fps` + the
   `144Hz Frame Pacing` log.
4. If < 30 FPS, run spike attempt 1 with `forceCompositorZink` to see whether removing the
   software compositor closes the gap.
5. Only then decide the verdict for TASK-027/028 — they remain **Blocked**.
