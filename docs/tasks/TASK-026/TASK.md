# TASK-026 — 100% GPU Rendering (Zink + GLES2 Host Blit), Dynamic Render Scale & Immersive Desktop UX

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Native Runtime Graphics, Host Presentation Pipeline & Desktop UX  
**Board:** [Central task board](../../../TASKS.md) is the workflow-status source.  
**Owner / harness:** Antigravity / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Write scope:** `bridge/wayland/`, `core/session/`, `core/proot/`, `app/`, `docs/tasks/TASK-026/`  
**Risk:** High  
**Status:** **In Progress** — Phase 0 landed (`ae60856` + nit fixes); **Phase 1 done and verified on-device** (GLES2/3 blit is now the default presentation path with automatic CPU fallback, live FPS pill deployed and reading `alw-host-query fps`); Phases 2–5 pending.  
**Depends on:** TASK-024 (DMA-BUF bridge + Hyprland session), TASK-025 (host bridge socket, rice assets, pointer-latency work).  
**Fallback preserved:** the LabWC Safe Mode profile (pixman / `wl_shm`) and the CPU `ANativeWindow_lock` blit path remain fully functional at all times; the GLES presenter falls back automatically and can be forced off with `ALW_GLES_BLIT=0` / `debug.alw.gles_blit=0`.

---

## 1. Problem Statement

After TASK-024/025 the Hyprland 0.57 desktop renders through the DMA-BUF bridge on the Xiaomi
Pad 8, but the pipeline is not yet a "100% GPU workstation":

| Area | Current state | Gap |
|---|---|---|
| Compositor renderer | `kms_swrast` on the PRoot virtual DRM node (`renderD128`) — forcing zink there fails GBM EGL init (`gbm platform: eglInitialize failed`, verified on-device) | Compositor is CPU-rendered; apps are GPU-rendered (hybrid split) |
| Guest apps | Turnip+zink via `alw-gui-run`/rice shims (verified `eglinfo`: `zink Vulkan 1.4(Adreno (TM) 825 (MESA_TURNIP))`) | Need zero-silent-llvmpipe verification + `eglinfo -p wayland` gate |
| Host presentation | Scalar CPU swizzle loop in `blitBufferToWindow` at up to 3200×2136 per frame | **Fixed in Phase 1** — GPU EGL/GLES2-3 texture blit is the default, with automatic CPU fallback |
| FPS visibility | None in-session | **Fixed in Phase 1** — `nativeGetActualFps()` → `RiceHostBridge` `fps` → live Quickshell pill (`AlwFpsShellGenerator` deploys it) |
| Render scale | Fixed full resolution | Dynamic 0.35×–1.0× with live `wl_output.mode` / `xdg_toplevel.configure` re-broadcast (no compositor restart) |
| Desktop UX | Android status/navigation bars consume space; HUD pill fixed; kitty opens bash | Immersive fullscreen (`WindowInsetsControllerCompat`), draggable/collapsible HUD, kitty+fish with CachyOS configs and fastfetch greeting |

## 2. Architecture — GPU split, host presenter & telemetry

```
┌──────────────────────── guest (Arch Linux ARM, PRoot) ───────────────────────┐
│  Hyprland 0.57 (compositor)                                                  │
│    EGL/GBM on /dev/dri/renderD128 (virtual node, dma-heap backed)            │
│    MESA_LOADER_DRIVER_OVERRIDE=kms_swrast  ← verified default                │
│    (zink here = "gbm platform: eglInitialize failed" — spike §3b re-tests)   │
│    └─ renders into zwp_linux_dmabuf_v1 LINEAR buffers                        │
│  Guest apps (kitty, Konsole, eglinfo, …) launched via alw-gui-run / shims    │
│    VK_ICD=freedreno (Turnip) · MESA_LOADER_DRIVER_OVERRIDE=zink              │
│    GALLIUM_DRIVER=zink · LIBGL_ALWAYS_SOFTWARE=0 · TU_DEBUG=noconform        │
│    → eglinfo -p wayland: zink (Turnip Adreno (TM) 825), never llvmpipe       │
└──────────────┬────────────────────────────────────────────────────────────────┘
               │ wl_shm / zwp_linux_dmabuf_v1 (SCM_RIGHTS)
┌──────────────▼──────────── Android host (wayland_bridge.cpp) ─────────────────┐
│  import → cursor sprite cache → present:                                     │
│    GPU path (default): glTexSubImage2D + fullscreen quad + eglSwapBuffers on │
│      the ANativeWindow (Adreno 825), ES3 preferred                           │
│    CPU path (automatic fallback): ANativeWindow_lock + scalar swizzle        │
│  pacing: Choreographer VSync → wl_callback.done at the live refresh rate (1x)│
│  telemetry: m_blitCounter → m_actualFps (1 s window) → nativeGetActualFps    │
│  dynamic output: m_renderScale (0.35–1.0) → ANativeWindow geometry +         │
│    broadcastOutputResizeLocked: wl_output.mode + xdg_toplevel/surface        │
│    .configure re-broadcast — Hyprland resizes without restarting             │
└──────────────┬────────────────────────────────────────────────────────────────┘
               │ AF_UNIX alw-host.sock (TASK-025 line protocol)
┌──────────────▼──────────── Guest rices / shims ───────────────────────────────┐
│  alw-host-query fps        → Quickshell alw-fps pill + Waybar custom module  │
│  alw-host-query scale get  → current render scale                            │
│  alw-host-query scale set <0.35..1.0> → live resize + HUD cycler             │
└───────────────────────────────────────────────────────────────────────────────┘
```

## 3. Phases

**Execution order: Phase 0 → Phase 1 → Phase 3b spike (time-boxed) → Phase 2 → Phase 4 → Phase 5.**
On spike bail-out: skip to Phases 4–5 immediately (Phase 2's remaining persistence work folds
into the Phase 4 commit window), then proceed to TASK-027.

### Phase 0 — Land the in-flight graphics work (bulk commit `ae60856`, nit fixes in the TASK-026 Phase 0 commit)
The working tree already contains: native `setRenderScale` (clamp 0.35–1.0, default 0.75) with
`broadcastOutputResizeLocked` re-emitting `wl_output.mode` + `xdg_toplevel.configure` +
`xdg_surface.configure`; `m_actualFps` (blit counter, 1 s window) with
`nativeGetActualFps`/`nativeGetRenderScale` JNI; `RiceHostBridge` `fps` / `scale get|set`
commands + `RiceGraphicsControl`; HUD scale-cycler + sticky-CTRL pills; metaState-merged
`mods_depressed` (Shift 0x1, Ctrl 0x4, Alt 0x8, Super 0x40) with change-deduped
`wl_keyboard.modifiers`; the EGL/GLES2-3 presenter behind `ALW_GLES_BLIT=1`; session-wide
Turnip ICD with the compositor held on `kms_swrast` (`EnvironmentGenerator`); zink env exported
for guest apps through all rice shims (`RiceShimGenerator`); `DRM_CAP_SYNCOBJ=0` in
`android_dri.c`; `qs -c alw-fps` autostart in `ConfigSynthesizer`.

Nit fixes before the commit:
1. `blitBufferToWindow` called `presentWithGl()` **twice per frame** when the presenter is
   enabled and ready (double `eglSwapBuffers`); restructure to a single call with CPU fallback
   on failure (`bridge/wayland/src/main/cpp/wayland_bridge.cpp`).
2. The `fakeroot` shim template's `else` branch lost its indentation when the zink env block
   was inserted (`core/proot/src/main/java/.../RiceShimGenerator.kt`).

### Phase 1 — Host GLES2/3 blit + live FPS pill — **DONE (2026-11-19, verified on-device)**

**Root cause** of "initializes and swaps but the frame never appears" was five defects in the
presenter, all fixed in `bridge/wayland/src/main/cpp/wayland_bridge.cpp`:
1. `setNativeWindow()` released the `ANativeWindow` without tearing down the presenter; the
   `m_glReady` short-circuit then kept swapping into a dead BufferQueue.
2. `resizeSurface()` called `ANativeWindow_setBuffersGeometry()` under a live `EGLSurface`,
   invalidating its swapchain (it fires from `surfaceChanged` → `attachSurface`).
3. `drawCursorOverlay()` and the CPU blit called `ANativeWindow_lock()` while EGL owned the
   window.
4. The fullscreen quad mapped the guest's top row to NDC `y = -1` → upside-down output.
5. The upload used optional `GL_BGRA_EXT`; core `GL_RGBA` + a `.bgr` shader swizzle is correct for
   little-endian ARGB/XRGB8888.

**Presentation is now GPU by default** with a per-frame automatic CPU fallback;
`ALW_GLES_BLIT=0` / `debug.alw.gles_blit=0` forces the CPU path for A/B testing. Instrumentation
added: `EGL_NATIVE_VISUAL_ID` vs window-format check, `eglQuerySurface` size log, and a periodic
`glReadPixels` probe sampled before `eglSwapBuffers`.

**FPS pill:** `AlwFpsShellGenerator` (`core/proot/.../shim/`) deploys
`~/.config/quickshell/alw-fps/shell.qml` (bundled resource) into every guest home,
non-destructively, and is wired into `ProotRunner.buildLaunchCommand`. Unit tests cover resource
loading, both-home deployment, the non-destructive invariant, custom homes and a missing rootfs.

**Finding — the ~2 FPS ceiling is the guest compositor, not the host blit.** GPU (`GLES blit …
fps 1.9–2.9`) and CPU (`ANativeWindow_lock`, `FPS 1.7–3.0`) paths measure the same rate; the CPU
path never touches EGL. Hyprland is CPU-rendering on `kms_swrast` at 2400×1602 with blur. Raising
it is Phase 3b (compositor-zink spike).

Evidence: [phase1-verification.md](evidence/phase1-verification.md),
[gpu_blit_hyprland_desktop.png](evidence/gpu_blit_hyprland_desktop.png),
[cpu_fallback_hyprland_desktop.png](evidence/cpu_fallback_hyprland_desktop.png),
[gles_presenter_gpu_path.log](evidence/gles_presenter_gpu_path.log),
[cpu_fallback_path.log](evidence/cpu_fallback_path.log).

### Phase 2 — Dynamic render scale (0.35×–1.0×) polish
Core landed in Phase 0. Remaining: persist the chosen scale (SharedPreferences, restored at
session start via `WaylandServerManager.setRenderScale`); document that render scale changes the
**`wl_output.mode` only** — Hyprland's Lua `monitor scale` stays untouched. Edge cases: cursor
sprite shadow invalidated on scale change (already cleared), odd-size alignment (already
`align2`), aspect letterbox mismatch (GL path handles; verify the CPU path), scale change while
a dmabuf buffer is mid-flight (guest rebuilds the swapchain from the configure serial).

### Phase 3 — Zero-silent-llvmpipe verification for guest apps
Provision `mesa-demos` (`eglinfo`) in the guest; inside an `alw-gui-run` shell,
`eglinfo -p wayland` must report zink (Turnip Adreno (TM) 825) — never llvmpipe — on every
platform section. Add a session-time watchdog: scan the session log for `llvmpipe` GL vendor
strings in guest apps and surface a HUD warning when detected.

### Phase 3b — Compositor-zink spike (time-boxed, bail-out rule)
**Status (2026-11-19): plumbing landed, spike attempt not run.** The toggle
(`gpu.forceCompositorZink`, `ALW_COMPOSITOR_ZINK`) is implemented and unit-tested, but the
attempt requires installing the build on the device, which HyperOS refused (see
[blocker-gate-2026-11-19.md](evidence/blocker-gate-2026-11-19.md) §4). Existing on-device
evidence stands: zink fails GBM EGL init on the virtual DRM node, so the **bail-out rule is
applied — toggle off by default, hybrid split retained**.

Run **after Phase 1 is verified working on the `kms_swrast` compositor** (Phase 1 is done — the
host blit is GPU by default and the desktop is visually verified). Goal:
full end-to-end GPU rendering for Hyprland too. Maximum **2 focused attempts** behind the
off-by-default profile toggle `gpu.forceCompositorZink`:
1. **Attempt 1 — direct zink on the virtual node:** force
   `MESA_LOADER_DRIVER_OVERRIDE=zink` + `GALLIUM_DRIVER=zink` for the compositor (drop the
   `kms_swrast` override in `EnvironmentGenerator` when the toggle is set) and capture the exact
   GBM/EGL failure with current Mesa on the virtual node.
2. **Attempt 2 — protocol/property alignment:** extend `android_dri.c` (and/or the bridge
   dmabuf feedback) so zink+GBM can initialize — e.g. matching DRM/PRIME properties and KMS
   resource fidelity Aquamarine's gbm path probes, or testing Aquamarine's native Wayland EGL
   platform without GBM.

**Bail-out rule:** if a spike attempt boots Hyprland and renders cleanly within the 1–2
attempts, keep `gpu.forceCompositorZink` enabled for the Turnip profile (still revertible via a
single profile flip). If it hits deep Mesa/GBM blockers, leave the toggle **off by default**,
keep the verified hybrid split (compositor `kms_swrast`, all guest apps Turnip+zink), and move
immediately to Phases 4–5 and then TASK-027 — no extra cycles on the spike.

### Phase 4 — Immersive fullscreen + draggable HUD
`MainActivity.kt` / `SessionSurfaceScreen.kt`:
`WindowCompat.setDecorFitsSystemWindows(window, false)` +
`WindowInsetsControllerCompat(window, window.decorView).hide(statusBars() | navigationBars())`
with `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, reapplied in `onResume`/on window focus; touch
handling intact (swipe reveals transient bars). `FloatingControlPill.kt`: drag via
`pointerInput` + `detectDragGestures` with a persisted offset; collapse behavior unchanged.
Edge cases: the drag gesture is consumed by the pill (never reaches the guest surface), insets
re-hide after a swipe-reveal, position survives rotation/session restart.

### Phase 5 — Fish + kitty + fastfetch greeting
`ArchProvisioningScriptBuilder`: add `fish` to the workstation package set. Import the host
CachyOS fish tree (`~/.config/fish/`) into `/home/alw` and `/root` `.config/fish/` following the
TASK-025 Lua-import pattern (guarded includes; **never overwrite existing guest dotfiles** —
Non-Destructive Invariant). `ProvisioningConfig.shell` → `/usr/bin/fish` for new profiles;
`ConfigSynthesizer` kitty autostart changes from `fastfetch; exec bash` to launching
`/usr/bin/fish` with the fastfetch greeting from `config.fish`. Edge cases: fish absent in an
older rootfs → fall back to the bash launch; `SHELL` env already follows the profile.

## 4. Acceptance Criteria

| Criterion | Description | Status |
|---|---|---|
| **AC-01** | Task registered with architecture, phases and AC table | **Done** |
| **AC-02** | GPU (EGL/GLES2-3) presentation on the Adreno 825 is the default path, verified visibly on-device; the CPU blit remains an automatic fallback and still renders (screenshots for both) | **Done** — `center=0xff513e3a` probe + [GPU](evidence/gpu_blit_hyprland_desktop.png) / [CPU](evidence/cpu_fallback_hyprland_desktop.png) screenshots |
| **AC-03** | `alw-host-query fps` returns live `fps/res/scale/gpu`; FPS pill visible on the Sekai Pop bar/dock; value tracks the measured rate (±1) | **Done** — `fps=1 res=2400x1602 scale=0.75 gpu=zink-adreno825`; pill shows the same value |
| **AC-04** | `alw-host-query scale set 0.5` (and the HUD cycler) resizes the Hyprland desktop live — `wl_output.mode` + `xdg_toplevel.configure` re-emitted, no compositor restart (screenshots at 0.5× and 1.0×) | Planned |
| **AC-05** | `eglinfo -p wayland` in a guest app reports zink (Turnip Adreno (TM) 825); zero llvmpipe vendor strings in the session | Planned |
| **AC-06** | Compositor-zink spike executed (max 2 attempts, right after Phase 1); outcome documented — enabled on clean boot, off-by-default with the hybrid split preserved on deep blockers; always revertible in one profile flip | Planned |
| **AC-07** | Status/navigation bars hidden in session; swipe shows transient bars (screenshot) | Planned |
| **AC-08** | HUD pill draggable + collapsible; position persists across session restarts | Planned |
| **AC-09** | Kitty launches fish with the imported CachyOS fish config and fastfetch greeting; bash fallback when fish is missing | Planned |
| **AC-10** | Unit tests + `./gradlew assembleDebug` green; evidence in `docs/tasks/TASK-026/evidence/`; commits pushed to `origin/main` | Planned (Phase 0 committed) |

## 5. Verification Plan

1. Phase 1 (**done**): GPU vs CPU screenshots, `glReadPixels` centre-pixel probe, frame-cadence
   log, SurfaceFlinger layer listing — see `evidence/phase1-verification.md`.
2. Phase 2/AC-04: `alw-host-query scale set 0.5` in a guest terminal + HUD cycler; screenshots
   at 0.5× and 1.0×; confirm Hyprland survived (no restart).
3. Phase 3/AC-05: `pacman -S mesa-demos`; `eglinfo -p wayland` output into evidence; log scan
   for `llvmpipe`.
4. Phase 3b: spike logs (compositor stdout) into evidence; decision recorded here.
5. Phase 4/5: screenshots (immersive bars hidden, draggable HUD), `kitty`/`fish` session log.
6. Regression: LabWC Safe Mode session still renders (CPU path untouched).

## 6. Risks & Mitigations

- **GLES presenter invisible frame** may be unresolvable quickly → CPU path stays the
  default-safe presenter; Phase 1 has an explicit rollback (unset `ALW_GLES_BLIT`).
- **Compositor-zink may be infeasible under PRoot** (GBM on the dma-heap-backed virtual node)
  → time-boxed spike with the bail-out rule; the hybrid split remains the shipped default.
- **ES2 contexts lack `GL_UNPACK_ROW_LENGTH`** → ES3 is required for padded strides (already
  enforced); anything else falls back to the CPU path for correctness.
- **Regression risk to the working session** → the presenter and the zink toggle are opt-in;
  LabWC Safe Mode and the TASK-023/024/025 behaviour remain untouched.

## 7. GPU Blocker Gate execution (2026-11-19)

Full record: [blocker-gate-2026-11-19.md](evidence/blocker-gate-2026-11-19.md).

- **llvmpipe relief — done.** `ConfigSynthesizer` now treats the compositor as software not
  only for `LLVMPIPE` but for every profile that is not on `forceCompositorZink` (i.e. the
  `kms_swrast` default). Blur **and** shadows are disabled there and re-enabled automatically
  on the GPU path. This was a live bug: the default 1–2 FPS profile was rendering with
  `blur = { enabled = true, size = 5, passes = 2 }`.
- **Zero-copy AHB/EGLImage — implemented in TASK-029 scope**, not yet verified on-device.
- **Gate verdict: not met / not measured.** HyperOS refused every APK install (silent 25 s
  `INSTALL_CANCELED_BY_USER`, no confirmation UI, `persist.security.adbinstall=1`), so neither
  the spike nor the FPS measurement ran. **TASK-027 and TASK-028 stay Blocked.**

## 8. VSync pacing fix and re-measurement (2026-11-19 23:18)

Full record: [vsync-fix-gate-2026-11-19.md](evidence/vsync-fix-gate-2026-11-19.md).

**Root cause of the 1–2 FPS baseline:** `onVsync()` in `wayland_bridge.cpp` had **no JNI
binding and no Kotlin caller**. The pacer therefore had no presentation clock and fell back to
its 250 ms dead-man timer — which is why 0.75× and 0.40× scale both measured ~3.8 FPS
(scale-independent ⇒ timer-bound, not pixel-bound).

**Fix:** `nativeOnVsync` JNI export (with `onVsync` moved to the public section) plus a
`Choreographer.FrameCallback` loop in `WaylandSurfaceView`, re-armed every frame and stopped in
`surfaceDestroyed`. VSync ticks now arrive every 16.7 ms.

| Metric | Before | After |
|---|---|---|
| Sustained window movement, 1.0× | ~3.8 FPS | **26.4–26.8 FPS** |
| Sustained window movement, 0.5× | ~3.8 FPS | **28.7 FPS** (peak) |
| Hyprland CPU (zink) | 341 % (kms_swrast) | **19–34 %** |
| Host blit, 1600×1068 | (CPU-only) | upload **0.72–1.13 ms**, draw+swap **0.60–2.14 ms** |

**Gate still NOT passed** (28.7 < 30), so TASK-027/028 remain Blocked. Remaining limit is
serialised latency, not throughput: zero-copy is still absent (this Adreno does not advertise
`EGL_EXT_image_dma_buf_import`, AHB import is rejected, padded stride 9728), PRoot syscall
translation costs 34–72 % CPU, and 250–440 ms hitch frames drag the average. GPU
utilisation/clock is unreadable without root (`/sys/class/kgsl/...` → `Permission denied`).
