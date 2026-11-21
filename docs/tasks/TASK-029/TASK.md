# TASK-029 — Zero-Copy `AHardwareBuffer` (`AHB`) Dmabuf Import & Presentation

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Native Runtime Graphics, Dmabuf Import & Host Presentation Pipeline  
**Board:** [Central task board](../../../TASKS.md) is the workflow-status source.  
**Owner / harness:** Antigravity / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Write scope:** `bridge/wayland/`, `core/proot/`, `docs/tasks/TASK-029/`  
**Risk:** High  
**Status:** **In Progress (2026-11-21)** — the dma-buf transport and its synchronisation are implemented and verified on the Pad 8: `zwp_linux_dmabuf_v1` v4 with LINEAR-only ARGB8888/XRGB8888 feedback carries Hyprland's frames, `zwp_linux_explicit_synchronization_v1` v2 (acquire-fence wait + `released(fence)` from `eglDupNativeFenceFDANDROID`) is advertised, and the mmap read now waits on the buffer's implicit read fence (`DMA_BUF_IOCTL_EXPORT_SYNC_FILE`). True zero-copy remains blocked by the platform: `EGL_EXT_image_dma_buf_import=0` and `AHardwareBuffer_createFromHandle` rejects the virtual node's `dma_heap` handle (rc=-22), so presentation stays `mmap` + `glTexSubImage2D`. Aquamarine does not bind the legacy explicit-sync protocol (0 binds); its successor `wp_linux_drm_syncobj_manager_v1` needs real DRM timeline syncobjs the app process does not have. Evidence: [2026-11-21-dmabuf-explicit-sync.md](evidence/2026-11-21-dmabuf-explicit-sync.md). Originally **Ready** — promoted into the **GPU Rendering Performance Blocker Gate** (see [TASKS.md](../../../TASKS.md)): sequenced after TASK-026 Phase 1 (done — the GLES2/3 blit is the verified GPU default) and the TASK-026 Phase 3b compositor-zink push, **before** TASK-027/028 can unblock. Planning-side spec only; the relief/presenter implementation stays with the agent executing TASK-026 (one writer per file).  
**Depends on:** TASK-026 Phase 1 (done — GLES2/3 host presenter, GPU default path), TASK-026 Phase 3b (compositor-zink push; this task applies to the `kms_swrast` fallback regardless of its outcome), TASK-024 (DMA-BUF bridge), TASK-025 (host bridge socket).  
**Fallback preserved:** the current `mmap` linear-DMA-BUF path and the CPU `ANativeWindow_lock` blit stay fully functional and are the automatic fallback whenever adapter/EGLImage import fails. The `ALW_GLES_BLIT=0` / `debug.alw.gles_blit=0` kill switches and the LabWC Safe Mode profile remain untouched.

---

## 1. Problem Statement

TASK-024 landed a working DMA-BUF bridge, but the import path is not zero-copy. In
`bridge/wayland/src/main/cpp/wayland_bridge.cpp`:

| Area | Current state | Gap |
|---|---|---|
| AHB import | `importAndRenderDmabuf()` optionally wraps the plane fd with `AHardwareBuffer_createFromHandle()` (gated behind `ALW_DMABUF_AHB=1` because HyperOS gralloc can crash in `SnapAllocCore::Retain()`) | Even when the AHB is accepted it is immediately `AHardwareBuffer_lock()`-ed and CPU-blitted |
| mmap fallback | linear `mmap` of `plane.fd` + scalar swizzle into the BufferQueue | Full CPU pixel copy at up to 3200×2136 per frame |
| Host presenter | `presentWithGl()` uploads CPU pixels with `glTexSubImage2D` and draws a fullscreen quad | The GPU path still receives pixels through the CPU |
| Buffer lifecycle | `wl_buffer.release` is sent immediately after import in the `wl_surface.commit` handler | A GPU texture that aliases the guest buffer cannot be held past the commit; unsafe for zero-copy |
| Format negotiation | `createDmabufFormatTableFd()` advertises only `ARGB8888`/`XRGB8888` + `DRM_FORMAT_MOD_LINEAR` | No `AHardwareBuffer` format mapping or explicit adapter capability probe |

The result: every guest frame crosses the CPU, which shows up as fixed per-frame cost and
limits high-refresh (120/144 Hz) headroom on the Adreno 825.

## 2. Architecture — AHB/EGLImage zero-copy path

```
┌──────────────────────── guest (Arch Linux ARM, PRoot) ───────────────────────┐
│  Hyprland 0.57 (kms_swrast) → zwp_linux_dmabuf_v1 buffers (LINEAR ARGB/XRGB)  │
│  SCM_RIGHTS plane fd →                                                            │
└──────────────┬────────────────────────────────────────────────────────────────┘
               │
┌──────────────▼──────────── Android host (wayland_bridge.cpp) ─────────────────┐
│  importAndRenderDmabuf()                                                      │
│    capability probe (once/session, cached):                                   │
│      EGL_KHR_image_base · EGL_ANDROID_image_native_buffer ·                    │
│      EGL_ANDROID_get_native_client_buffer · GL_OES_EGL_image                  │
│    ┌── zero-copy path (default when probe passes) ───────────────────────────┐│
│    │ AHardwareBuffer_createFromHandle(plane.fd)  [guarded, never fatal]      ││
│    │   → eglGetNativeClientBufferANDROID(ahb)                                ││
│    │   → eglCreateImageKHR(EGL_NATIVE_BUFFER_ANDROID)                        ││
│    │   → glEGLImageTargetTexture2DOES(texture)                               ││
│    │   → fullscreen quad (+ .bgr swizzle) → eglSwapBuffers on Adreno 825     ││
│    └─────────────────────────────────────────────────────────────────────────┘│
│    ┌── automatic fallback (unchanged) ───────────────────────────────────────┐│
│    │ mmap(plane.fd) → presentWithGl() CPU upload → ANativeWindow_lock()       ││
│    └─────────────────────────────────────────────────────────────────────────┘│
│  lifecycle: imported image pool keyed by (fd, w, h, format, modifier)        │
│             wl_buffer.release deferred until after eglSwapBuffers             │
│  telemetry: path counter egl=|ahb-cpu=|mmap-cpu= → nativeGetActualFps         │
└───────────────────────────────────────────────────────────────────────────────┘
```

### 2.1 Required engineering

- **Probe, do not assume.** Query the extension strings and resolve
  `eglGetNativeClientBufferANDROID`, `eglCreateImageKHR`, `eglDestroyImageKHR` and
  `glEGLImageTargetTexture2DOES` through `eglGetProcAddress()`. Cache the result once per
  session and surface it in the `dmabuf: path=…` log line.
- **Keep the vendor-gralloc guard.** `AHardwareBuffer_createFromHandle()` remains behind a
  guarded, non-fatal attempt. A rejection or crash-prone device must transparently take the
  mmap path; `ALW_DMABUF_AHB=0` forces the CPU path for triage.
- **Single present path per frame.** Extend the presenter instead of adding a second swap:
  `presentDmabufWithGl()` draws with the existing quad pipeline and owns the swap; the
  commit handler must not also call `blitBufferToWindow()` for the same frame.
- **Deferred release.** The current commit handler releases the `wl_buffer` immediately.
  Zero-copy needs the buffer to stay alive until the frame has been consumed; keep a bounded
  pool of imported images and release after `eglSwapBuffers()` (or on the next commit).
- **Byte order and format.** Guest ARGB/XRGB buffers are `B,G,R,A` in little-endian memory.
  Map `DRM_FORMAT_ARGB8888` → `AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM` and
  `DRM_FORMAT_XRGB8888` → `AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM`, and either import as
  BGRA where gralloc accepts it or keep the existing fragment-shader `.bgr` swizzle. Verified
  with a known test pattern, not by eye.
- **Geometry.** Preserve the existing aspect-preserving letterbox and render-scale behaviour
  (`scaledWidth()`/`scaledHeight()`, `eglQuerySurface`).
- **Tear-down.** `destroyGlPresenter()`, `resizeSurface()` and `setRenderScale()` must also
  destroy any live `EGLImage`s and their AHB references; `nativeSetSurface(null)` must not
  leave dangling images.

## 3. Phases

### Phase 0 — Capability probe & instrumented baseline
- Add the EGL/GLES extension probe and a cached `m_dmabufImportMode` (`egl` / `ahb-cpu` / `mmap-cpu`).
- Count frames per path and log the selected path once per second.
- Capture baseline evidence: current CPU path cadence, `dumpsys SurfaceFlinger`, screenshot.

### Phase 1 — AHB/EGLImage import and zero-copy present
- Implement `createAhbFromDmabufPlane()` + `createEglImageFromAhb()` with a per-buffer cache.
- Add `presentDmabufWithGl(AHardwareBuffer*)`; reuse the quad, mip-free sampling and swizzle.
- Guarantee exactly one present path per committed frame; add the deferred-release pool.
- Wire `ALW_DMABUF_AHB` to opt *in* to the import when the probe passes; keep `0` as force-off.

### Phase 2 — Format, stride and lifecycle correctness
- Validate `plane.offset`, `plane.stride`, `width/height` and modifier; reject anything the
  adapter cannot represent and fall back per frame.
- Verify channel order with a colour test pattern; verify letterbox at 0.5× and 1.0× scale.
- Handle scale change / surface resize while a buffer is in flight: drop the pool, rebuild.
- Confirm no concurrent `ANativeWindow_lock()` while the EGL presenter owns the window.

### Phase 3 — Fallback hardening, tests and evidence
- Automatic mid-session downgrade to mmap/CPU if `eglCreateImageKHR` or the adapter import
  starts failing; latched with a recovery probe.
- Extend unit-testable JNI/Kotlin seams (path selection, format mapping, fallback decision).
- Record evidence: logs showing `path=egl`, screenshots, frame pacing, `dumpsys`.

## 4. Acceptance Criteria

| Criterion | Description | Status |
|---|---|---|
| **AC-01** | Task registered with architecture, phases and this AC table | **Done** |
| **AC-02** | On the Xiaomi Pad 8 the log reports `dmabuf: path=egl` and the desktop renders from the imported `AHardwareBuffer` (screenshot) | **Implemented, unverified** — code landed + built; the device refused the APK install ([gate record](../TASK-026/evidence/blocker-gate-2026-11-19.md) §4) |
| **AC-03** | The zero-copy path performs no `AHardwareBuffer_lock()`/`mmap()` pixel read per frame (instrumented assert/log); CPU fallback unchanged and selectable via `ALW_GLES_BLIT=0` / `ALW_DMABUF_AHB=0` | **Implemented, unverified** — `presentDmabufZeroCopy()` does no lock/mmap/`glTexSubImage2D`; `ALW_DMABUF_ZEROCOPY=0` selects the old path |
| **AC-04** | Colour order and aspect letterbox are correct at render scale 0.5× and 1.0× (screenshots + test pattern) | Planned |
| **AC-05** | Buffer lifecycle is safe: deferred `wl_buffer.release`, no double-present, sustained 10-minute session without stalls or leaks | Planned |
| **AC-06** | If HyperOS gralloc rejects `createFromHandle` the bridge falls back to mmap without crashing; `ALW_DMABUF_AHB=0` reproduces the current path | Planned |
| **AC-07** | `nativeGetActualFps()` counts the zero-copy path; FPS pill/`alw-host-query fps` still report live values | Planned |
| **AC-08** | Unit tests + `./gradlew assembleDebug` green; evidence in `docs/tasks/TASK-029/evidence/`; commit pushed to `origin/main` | Planned |
| **AC-09** | **Gate contribution:** with this task landed, the presentation path performs **zero CPU pixel copies** per frame, and together with the TASK-026 relief + compositor work the pipeline sustains **>= 30 FPS during active window movement/animation** on the Xiaomi Pad 8 (60 FPS target) — the verdict that unblocks TASK-027/028 | **Not met** — unmeasured (install blocked); TASK-027/028 remain Blocked |

## 5. Verification Plan

1. Probe: boot a session, capture the extension list and the selected `dmabuf: path=` value.
2. Zero-copy: screenshot the Hyprland desktop; confirm frames are presented through the EGL
   path and that `eglSwapBuffers` succeeds every frame.
3. Colour/geometry: render a known RGB test pattern in-guest; compare at 0.5× and 1.0×; check
   letterbox edges.
4. Lifecycle: 10-minute soak with a terminal + animated client; watch for `wl_buffer.release`
   starvation, stalls, or an FPS cliff.
5. Fallback: run with `ALW_DMABUF_AHB=0` and with `ALW_GLES_BLIT=0`; confirm identical output
   through the CPU path.
6. Regression: LabWC Safe Mode session still renders.

## 6. Risks & Mitigations

- **Vendor gralloc crash on raw fd import** (observed `SnapAllocCore::Retain()` on-device) →
  keep the guarded attempt + per-session capability latch; failure always degrades to mmap.
- **EGLImage lifetime vs. swap** → pooled imports with deferred `wl_buffer.release`; never
  destroy an image that the current frame still samples.
- **Channel-order mistakes** → reuse the proven `.bgr` swizzle and validate with a test
  pattern before declaring success.
- **Stride/offset/format variants** → explicit validation and per-frame fallback rather than
  rendering undefined memory.
- **Regression risk to the working bridge** → zero-copy is the default only when the probe
  passes; both kill switches and the CPU presenter stay intact.
