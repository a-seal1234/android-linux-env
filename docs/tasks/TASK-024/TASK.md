# TASK-024 — Zero-Copy DMA-BUF Pipeline & Virtual DRM Render Node (Hyprland / KWin 6.8 Unlock)

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Native Runtime Architecture, Wayland Protocol Extension & GPU Buffer Plumbing  
**Board:** [Central task board](../../../TASKS.md) is the workflow-status source.  
**Owner / harness:** Antigravity / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Write scope:** `core/proot/` (vendored `lib_proot.so` + rebuilt proot sources), `bridge/wayland/`, `core/session/`, `docs/tasks/TASK-024/`  
**Risk:** Critical  
**Status:** In Progress — Stages 1–3 implemented and verified on-device (virtual DRM node, GBM/PRIME export, `zwp_linux_dmabuf_v1` v4, DMA-BUF compositing). Compositor unlock blocked on a Mesa `dri_gbm` segfault on the virtual node (AC-07/AC-08).
**Depends on:** TASK-023 (AC-08..AC-11: in-app PRoot works, SIGSYS suppressed, SHM desktop verified).  
**Fallback preserved:** the LabWC Safe Mode profile (pixman / `wl_shm`) must remain fully functional.

---

## 1. Problem Statement

TASK-023 established that **both** desktop compositors gate hardware-accelerated startup on DMA-BUF
capabilities that the current bridge/sandbox do not provide:

| Compositor | Gate (source) | Missing capability |
|---|---|---|
| KWin 6.8.2 | `WaylandBackend::supportedCompositors()` — `src/backends/wayland/wayland_backend.cpp:608` returns `OpenGLCompositing` only when `m_display->linuxDmabuf() && m_renderDevice` | 1) `zwp_linux_dmabuf_v1` on the host bridge; 2) a DRM render node reachable through `GpuManager::compatibleRenderDevice()` (`/dev/dri/*`) |
| Hyprland 0.57.2 / Aquamarine 0.16 | `src/backend/Wayland.cpp` — backend refuses to start unless `zwp_linux_dmabuf_v1` + `linux_dmabuf` feedback `mainDevice` exist | 1) `zwp_linux_dmabuf_v1` + feedback; 2) DRM device for feedback / GBM allocation |

The unrooted Android sandbox exposes `/dev/kgsl-3d0` (KGSL) but **no** `/dev/dri` render node and the
Android Wayland bridge speaks `wl_shm` only. This task closes both gaps.

## 2. Architecture — Zero-Copy DMA-BUF + Virtual DRM Render Node

```
┌─────────────────────────── Guest (Arch Linux ARM, PRoot) ───────────────────────────┐
│  Compositor (KWin / Hyprland)                                                        │
│    EGL/GBM  ── open("/dev/dri/renderD128") ──► [PRoot dma-buf extension]             │
│    DMA-BUF fd ── zwp_linux_dmabuf_v1.create/params.add(fd)/create_immediate ──┐       │
└───────────────────────────────────────────────────────────────────────────────┼──────┘
                                                                                │ SCM_RIGHTS
┌──────────────────────────── Android app (Wayland bridge, wayland-0) ──────────▼──────┐
│  recvmsg(SCM_RIGHTS) → import plane fds                                              │
│    primary: native_handle_t → AHardwareBuffer_createFromHandle → EGLImage →          │
│             GL_TEXTURE_EXTERNAL_OES → render to ANativeWindow (GPU, zero-copy)       │
│    fallback: mmap(linear dmabuf) → blit BufferQueue (CPU, never crashes)             │
│  wl_buffer.release on buffer destruction                                             │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

### 2.1 Virtual DRM render node (`lib_proot.so`)

PRoot gains a compiled-in extension (source kept in-tree under `core/proot/native/`) that fakes a
render node inside the guest, backed by Android's DMA-BUF heap:

- `statx` / `fstat` / `newfstatat` / `fstatat64` on `/dev/dri/renderD128` → spoof `S_IFCHR`,
  `st_rdev = makedev(226, 128)` (the canonical `vgem`/`renderD` major).
- `getdents64` on `/dev/dri` → synthetic `renderD128` entry.
- `openat("/dev/dri/renderD128")` → backed by `/dev/dma_heap/system` (fallback
  `/dev/dma_heap/qcom,system`), tracked as a virtual DRM fd.
- DRM ioctls on tracked fds are answered by PRoot:
  `DRM_IOCTL_VERSION` (`"vgem"`), `DRM_IOCTL_GET_CAP` (`DUMB_BUFFER=1`, `PRIME=3`),
  `MODE_CREATE_DUMB` / `MODE_MAP_DUMB` / `MODE_DESTROY_DUMB`, `PRIME_HANDLE_TO_FD`
  (allocates a real DMA-BUF via `DMA_HEAP_IOCTL_ALLOC` and hands the fd to the tracee),
  `PRIME_FD_TO_HANDLE`.
- The `linux-dmabuf` feedback `main_device` advertised by the bridge is `makedev(226, 128)`,
  matching the virtual node, so `GpuManager::compatibleRenderDevice()` has a device to latch onto.

### 2.2 `zwp_linux_dmabuf_v1` v4 on the bridge (`wayland_bridge.cpp`)

- `zwp_linux_dmabuf_v1` (v4) global, `zwp_linux_buffer_params_v1`, `zwp_linux_dmabuf_feedback_v1`.
- Format table over a sealed `memfd`: `DRM_FORMAT_ARGB8888` + `DRM_FORMAT_XRGB8888`, each with
  `DRM_FORMAT_MOD_LINEAR (0x0)`; `main_device = makedev(226, 128)`.
- `create` / `create_immediate` → `wl_buffer` object carrying imported plane fds; fds are closed on
  buffer destruction; `wl_buffer.release` is emitted after each composition.
- Import on `wl_surface.commit`: `AHardwareBuffer` + `EGLImage` + `GL_TEXTURE_EXTERNAL_OES` primary
  path, `mmap`+upload fallback.

## 3. Acceptance Criteria

| Criterion | Description | Status |
|---|---|---|
| **AC-01** | TASK-024 registered with architecture + acceptance criteria | **Done** |
| **AC-02** | Guest sees `/dev/dri/renderD128` as char device `226,128` (`stat`, `getdents64`) | **Done** — `ls -l` → `crw-rw-rw- 226, 128 renderD128`, `stat` → `character special file e2:80 666` |
| **AC-03** | `gbm_create_device()` on the virtual node succeeds inside the guest | **Done** — `gbm_create_device() = ok`, `gbm_bo_create(320x240)` ok (stride 1280), **`gbm_bo_get_fd()` = real dma-buf fd with working mmap** (PRIME export via chained `DMA_HEAP_IOCTL_ALLOC`) |
| **AC-04** | `zwp_linux_dmabuf_v1` v4 + feedback global advertised; format table over `memfd` | **Done** — Aquamarine log: `Got format AR24/XR24 with modifier LINEAR`, `Got main device`, `Got done` |
| **AC-05** | DMA-BUF `wl_buffer` imported and composited (EGL primary path, mmap fallback) | **Done (mmap fallback)** — `dmabuf: AHardwareBuffer_createFromHandle rejected (rc=5); using mmap fallback`, `mmap fallback blit active (640x480)` |
| **AC-06** | Standalone dmabuf EGL client renders through `wayland-0` | **Done** — `alw-dmabuf-client` allocates from `/dev/dma_heap/system`, submits `zwp_linux_dmabuf_v1`, `DMABUF_CLIENT_OK`; screen shows the gradient ([screenshot](evidence/screen_dmabuf_client.png)) |
| **AC-07** | Hyprland passes `CBackend::create()` and renders desktop + Konsole | **Blocked** — Aquamarine now passes registry/dmabuf/feedback/main-device/node-open and reaches `CGBMAllocator::create()`; `gbm_create_device` inside that path **segfaults in Mesa's `dri_gbm.so`** on the virtual node (proot: `terminated with signal 11`). See [aquamarine probe](evidence/aquamarine_probe.cpp) output in §6. |
| **AC-08** | KWin 6.8 passes `supportedCompositors()` and renders Plasma + Konsole | **Blocked (same gate)** — KWin needs a `RenderDevice` from `GpuManager` (libdrm enumeration/udev) and an EGL/GBM context, which hits the same Mesa `dri_gbm` crash. |
| **AC-09** | LabWC Safe Mode (SHM) remains functional | **Done** — LabWC 0.20.2 + Konsole 26.8.3 render normally; default profile left on LABWC |
| **AC-10** | Unit tests pass; screenshot evidence captured; commit pushed to `origin/main` | **Done** — `./gradlew test` green; evidence in `docs/tasks/TASK-024/evidence/`; commit pushed |

## 5. Verified on-device evidence (Xiaomi Pad 8, `d370854b`)

```
# guest virtual node
$ ls -l /dev/dri/
crw-rw-rw- 1 alarm alarm 226, 128 renderD128
$ stat -c "%F %t:%T %a" /dev/dri/renderD128
character special file e2:80 666

# gbm / PRIME export on the virtual node
open(/dev/dri/renderD128, O_RDWR) = 3
gbm_create_device() = 0x...
gbm_bo_create(320x240 ARGB8888) = 0x...
  gbm_bo_get_fd = 6
  mmap(dmabuf fd) = OK

# bridge import of a client dma-buf
dmabuf buffer 10 created: 640x480 fmt=0x34325241 planes=1 (path=immediate)
dmabuf: AHardwareBuffer_createFromHandle rejected (rc=5); using mmap fallback
dmabuf: mmap fallback blit active (640x480 stride=2560 fmt=0x34325241)
DMABUF_CLIENT_OK

# Aquamarine (Hyprland backend) probe
zwp_linux_dmabuf_v1: Got format AR24 with modifier LINEAR
zwp_linux_dmabuf_v1: Got main device
zwp_linux_dmabuf_v1: Got node /dev/dri/renderD128
zwp_linux_dmabuf_v1: opened node /dev/dri/renderD128 with fd 8
reopenDRMNode: opening node /dev/dri/renderD128
[dri_gbm.so load] -> proot: terminated with signal 11
```

## 6. Remaining blockers (next increment)

1. **Mesa `dri_gbm`/loader crash on the virtual node** — the loader resolves the
   driver via `/proc/fdinfo`/sysfs (absent) and falls back to `dri_gbm.so`, which
   segfaults during screen creation on a node that only implements the GEM/PRIME
   ioctl subset. Options: implement the missing ioctls (`GET_RESOURCES`,
   `GET_CAP` extras, `PRIME` import) and/or interpose the Mesa loader with a
   forced `swrast`/`kms_swrast` driver path; or provide a userspace "vgem" shim
   library (`vgem_gbm.so`) matching Mesa's expectations.
2. **AHardwareBuffer import** — HyperOS gralloc rejects the raw `native_handle`
   (`rc=5`); the mmap fallback composites correctly. Resolving the vendor handle
   layout would give a true GPU zero-copy present path.
3. With (1) fixed, Hyprland/KWin should pass their allocator gates and reach
   renderer initialization (Turnip is available for guest Vulkan; the compositor
   GL path must use the virtual node's GBM/swrast).

## 4. Verification Plan

1. Guest: `stat /dev/dri/renderD128`, `ls -l /dev/dri`, `gbm_create_device()` probe.
2. Bridge: `weston-simple-dmabuf-egl` (or `eglinfo -B`) as a direct client of `wayland-0`.
3. Compositors: Hyprland (`GALLIUM_DRIVER=zink`) and KWin 6.8 with Turnip/Zink, then Konsole with
   `fastfetch` / `glxinfo -B`; screenshots into `docs/tasks/TASK-024/evidence/`.
4. Regression: LabWC Safe Mode screenshot.

## 5. Risks & Mitigations

- **Virtual DRM node fidelity**: many ioctls exist; only the subset GBM/Mesa needs is emulated.
  Mitigation: answer unknown ioctls with `-ENOTTY` and log; prefer `vgem` semantics.
- **HyperOS gralloc**: `AHardwareBuffer_createFromHandle()` may reject a raw `native_handle_t`
  layout. Mitigation: documented `mmap` linear fallback path keeps rendering alive.
- **Zink WSI**: Vulkan `VK_KHR_wayland_surface` is not implemented by Turnip; compositors must use
  GL via EGL/GBM on the virtual node (dmabuf), not swapchain WSI.
- **Regression risk to working session**: LabWC Safe Mode and all TASK-023 behaviour are kept
  untouched; the virtual node/extension is additive and feature-gated.
