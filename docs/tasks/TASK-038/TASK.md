# TASK-038 — Google ANGLE (`GLES -> Turnip Vulkan`) Guest Renderer Backend Option

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Guest Graphics Stack, Renderer Backend Selection & Performance Verification  
**Board:** [Central task board](../../../TASKS.md) is the workflow-status source.  
**Owner / harness:** Antigravity / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Write scope:** `core/proot/`, `core/session/`, `app/`, `docs/tasks/TASK-038/`  
**Risk:** Medium  
**Priority:** **P0 — top of the `Ready` queue** (primary architectural fix for the 30–50 ms/frame guest bottleneck; see §7).  
**Status:** **In Progress** — Phase 2 (backend selector + store) implemented 2026-11-20 (`a2e6c76`+); Phase 1 (aarch64 bundle) blocked on producing the artifact (no upstream aarch64 package exists); Phase 3 verification pending the bundle. Mesa Zink remains the active default today and is proven correct (selector resolves `zink` when the store is empty).  
**Depends on:** TASK-009 (Turnip KGSL driver store + `VK_ICD_FILENAMES`), TASK-024 (virtual DRM render node), TASK-026 Phase 1 (GLES host presenter), TASK-035 (linear dma-buf constraints + padded-row repack), TASK-037 Phase 2 (Electron/Chromium Ozone wrapper defaults — reuse its env plumbing).  
**Verification target:** Xiaomi Pad 8, wireless ADB `192.168.51.161:44021` (port is reassigned on reboot — always re-run `adb devices`).  
**Fallback preserved:** Mesa Zink (`GALLIUM_DRIVER=zink`) stays the instant default whenever `/opt/angle/lib` is absent or `ALW_GL_BACKEND=zink` is set; the host bridge pipeline (`wayland_bridge.cpp`, one `wl_buffer`/`dma-buf` presentation path) is untouched by either backend.

---

## 1. Problem Statement

Guest rendering on the Adreno 825 currently goes through **Mesa Zink** (`GALLIUM_DRIVER=zink`,
`MESA_LOADER_DRIVER_OVERRIDE=zink`, `TU_DEBUG=noconform,noubwc` — TASK-026/TASK-035). Measured
on the Pad 8 this costs 30–50 ms of CPU translation per frame, and the same stack produces UBWC
tiling artifacts plus instability when Hyprland, Quickshell and heavy Chromium/Electron clients
render concurrently.

Google ANGLE's Vulkan backend is engineered for Adreno: batched draw calls, explicit
synchronization-barrier handling, and crash resilience. It sits *behind* the same EGL/GLES
surface API, so the bridge keeps its single `wl_buffer`/`dma-buf` presentation pipeline and no
native change is required.

## 2. Architecture

```
guest app (Hyprland / qs / Chromium / Electron)
  └─ libEGL.so.1 / libGLESv2.so.2   ← selected at process start
       ├─ ANGLE (default when /opt/angle/lib exists)   ANGLE_DEFAULT_PLATFORM=vulkan
       │    └─ Vulkan -> Turnip ICD (/opt/drivers/turnip/active, TASK-009)
       └─ Mesa Zink (fallback, ALW_GL_BACKEND=zink)
            └─ Vulkan -> Turnip ICD
  both -> zwp_linux_dmabuf_v1 buffers, DRM_FORMAT_MOD_LINEAR only (TASK-035)
       -> alw-gui-run shim env -> bridge presentation (wayland_bridge.cpp, unchanged)
```

Selection is environment-only: `alw-gui-run` (and the session env from
`EnvironmentGenerator`) prepend `/opt/angle/lib` to `LD_LIBRARY_PATH` and export
`ANGLE_DEFAULT_PLATFORM=vulkan` when the bundle is present and `ALW_GL_BACKEND` is not `zink`.

**Packaging reality (verified before planning):** there is **no aarch64 ANGLE package** in
archlinuxarm nor a maintained `aarch64` AUR build (the AUR `angle` PKGBUILD builds `-m64 -msse3`
x86_64 only), so Phase 1 must produce the bundle itself: a cross/native build of
`ANGLE_ENABLE_VULKAN` + `ANGLE_USE_WAYLAND` + `ANGLE_USE_VULKAN_DISPLAY`, or a pinned upstream
artifact, installed with a SHA-256 manifest like the Turnip driver store (TASK-009).

## 3. Phases

**Phase 1 — `aarch64` glibc ANGLE packaging & runtime provisioning.**
Produce a verified `aarch64` glibc bundle with Vulkan/GBM/DMA-BUF support: `libEGL.so.1`,
`libGLESv2.so.2` (+ `libvk_swiftshader`/`vk` shim if the build emits one), installed into the
guest rootfs at `/opt/angle/lib` by a new provisioning step (mirroring
`TurnipDriverManager`'s store/validation patterns: version directory, SHA-256 manifest,
`ELF` ABI check via `ElfAbiValidator`, atomic activate). A setup/verification script inside the
guest (`/usr/local/bin/alw-angle-verify`) runs `eglinfo`/`es2_info` and prints the selected
renderer.

**Phase 2 — renderer backend selector (`RiceShimGenerator.kt`, `ConfigSynthesizer.kt`, `EnvironmentGenerator.kt`).**
Add `ALW_GL_BACKEND` (`angle` | `zink`, default `angle` when `/opt/angle/lib` is present):
`alw-gui-run` prepends `/opt/angle/lib` to `LD_LIBRARY_PATH` and exports
`ANGLE_DEFAULT_PLATFORM=vulkan`, dropping the Mesa zink loader overrides for that process;
`zink` remains a one-line fallback (`ALW_GL_BACKEND=zink`) and the default whenever the bundle
is absent. The session compositor path is untouched (it stays on `kms_swrast`, TASK-026).
`DRM_FORMAT_MOD_LINEAR`-only advertisement (TASK-035) must be preserved for both backends, and
the padded-row repack must be verified against ANGLE-allocated buffers.

**Phase 3 — on-device A/B benchmark & stability verification (`192.168.51.161:44021`).**
`eglinfo -p wayland` / `es2_info` must report `ANGLE (Turnip Adreno 825)` with the selector on
`angle`; compare drag FPS and frame time against Zink (same scene, same render scale), and run
Quickshell / Firefox / Electron (Antigravity) stability passes. Evidence in
`docs/tasks/TASK-038/evidence/` (logs + screenshots + FPS samples).

## 4. Acceptance Criteria

| Criterion | Description | Status |
|---|---|---|
| **AC-01** | Task registered with architecture, phases and AC table | **Done** |
| **AC-02** | Verified `aarch64` ANGLE bundle installs to `/opt/angle/lib` with SHA-256 + ELF ABI validation and an in-guest verify script | **Storage plumbing done** — `AngleBundleManager` (`core/proot/.../driver/`) implements the versioned store, `active-version` pointer, per-library SHA-256 and glibc-aarch64 ELF validation (rejecting Bionic), unit-tested; **artifact itself still missing** (no upstream aarch64 package) |
| **AC-03** | Backend selector: `ALW_GL_BACKEND=angle` (default when installed) prepends `/opt/angle/lib` + `ANGLE_DEFAULT_PLATFORM=vulkan`; `ALW_GL_BACKEND=zink` restores Zink instantly | **Done (code, 225 tests)** — `alw-gui-run` shim selector with bundle-presence detection + zink fallback when absent; `ProotRunner` binds the active bundle to `/opt/angle/lib` (step 8b) and seeds `ALW_GL_BACKEND` in the guest env for non-shim launches (profile override wins) |
| **AC-04** | `eglinfo`/`es2_info` reports `ANGLE (Turnip Adreno 825)`; `DRM_FORMAT_MOD_LINEAR` preserved (no tiling artifacts) | Planned |
| **AC-05** | A/B against Zink: drag FPS and frame time measured, with the 30–50 ms/frame translation cost demonstrably reduced or a documented verdict if not | Planned |
| **AC-06** | Quickshell + Firefox + Electron stability passes; unit tests + `./gradlew assembleDebug` green; evidence recorded; pushed to `origin/main` | Planned |

## 5. Verification Plan

1. Unit: backend-selection logic (`ALW_GL_BACKEND` × bundle presence), env assembly in
   `RiceShimGenerator`/`EnvironmentGenerator` generator tests.
2. On-device: `alw-angle-verify`, `eglinfo`, `es2_info`; drag-FPS sampling via
   `alw-host-query fps` for both backends; Quickshell popups, Firefox and Antigravity launches.
3. Regression: `ALW_GL_BACKEND=zink` reproduces today's behaviour exactly; host presenter
   untouched; TASK-035 padded-row repack still correct under ANGLE buffers.

## 6. Risks & Mitigations

- **No upstream aarch64 ANGLE package (verified)** → Phase 1 owns the build: cross-compile or
  pinned artifact with checksums; if the build proves impractical, the Zink default stays and
  this task records the verdict rather than blocking the queue.
- **ANGLE + Turnip Vulkan WSI gaps** (Wayland dmabuf modifiers) → keep LINEAR-only advertisement
  and the import guard; verify with `es2_info` before benchmarking.
- **Chromium/Electron may ship their own ANGLE copy** → scope the expectation to guest system
  GLES clients; document per-app overrides.
- **Perf claims must be measured, not assumed** → AC-05 requires same-scene A/B numbers; a
  negative result is a valid, documented outcome.

## 7. Why this is P0 (board note)

The measured 30–50 ms/frame guest CPU cost (TASK-026 FPS work) is dominated by the Mesa Zink
translation layer, and the same layer is the source of the remaining tiling/instability reports.
Every downstream feature (input latency, Quickshell polish, Firefox/Electron support in
TASK-036/TASK-037) inherits that cost, so this backend swap is sequenced ahead of TASK-036 and
TASK-037 in the `Ready` queue, while TASK-035 verification continues.
