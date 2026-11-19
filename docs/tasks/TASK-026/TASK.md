# TASK-026 — 100% GPU Rendering (Zink + GLES2 Host Blit), Dynamic Render Scale & Immersive Desktop UX

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Native Runtime Graphics, Host Presentation Pipeline & Desktop UX  
**Board:** [Central task board](../../../TASKS.md) is the workflow-status source.  
**Owner / harness:** Antigravity / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Write scope:** `bridge/wayland/`, `core/session/`, `core/proot/`, `app/`, `docs/tasks/TASK-026/`  
**Risk:** High  
**Status:** **In Progress** — Phase 0 (dynamic render scale, live FPS, metaState→XKB mods, EGL/GLES2-3 presenter behind `ALW_GLES_BLIT=1`, app-level Turnip+zink split) lands with the registration commit; Phases 1–5 pending.  
**Depends on:** TASK-024 (DMA-BUF bridge + Hyprland session), TASK-025 (host bridge socket, rice assets, pointer-latency work).  
**Fallback preserved:** the LabWC Safe Mode profile (pixman / `wl_shm`) and the CPU `ANativeWindow_lock` blit path must remain fully functional at all times; the GLES presenter is opt-in until verified.

---

## 1. Problem Statement

After TASK-024/025 the Hyprland 0.57 desktop renders through the DMA-BUF bridge on the Xiaomi
Pad 8, but the pipeline is not yet a "100% GPU workstation":

| Area | Current state | Gap |
|---|---|---|
| Compositor renderer | `kms_swrast` on the PRoot virtual DRM node (`renderD128`) — forcing zink there fails GBM EGL init (`gbm platform: eglInitialize failed`, verified on-device) | Compositor is CPU-rendered; apps are GPU-rendered (hybrid split) |
| Guest apps | Turnip+zink via `alw-gui-run`/rice shims (verified `eglinfo`: `zink Vulkan 1.4(Adreno (TM) 825 (MESA_TURNIP))`) | Need zero-silent-llvmpipe verification + `eglinfo -p wayland` gate |
| Host presentation | Scalar CPU swizzle loop in `blitBufferToWindow` at up to 3200×2136 per frame | GPU (EGL/GLES) texture blit on `ANativeWindow`; presenter exists behind `ALW_GLES_BLIT=1` but the composed frame does not reach the surface yet |
| FPS visibility | None in-session | `nativeGetActualFps()` → `RiceHostBridge` `fps` command → live pill on the Sekai Pop bar/dock |
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
│    GPU path (opt-in ALW_GLES_BLIT=1): glTexSubImage2D + fullscreen quad +    │
│      eglSwapBuffers on the ANativeWindow (Adreno 825), ES3 preferred         │
│    CPU path (default): ANativeWindow_lock + scalar swizzle                   │
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

### Phase 0 — Land the in-flight graphics work (this commit)
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

### Phase 1 — Host GLES2/3 blit + live FPS pill
Diagnose the known on-device issue: the GLES presenter initializes on the Adreno 825 and swaps,
but the composed frame does not reach the surface, so the CPU blit stays the default. Checklist
in `bridge/wayland/src/main/cpp/wayland_bridge.cpp`:
- (a) exactly one present path per frame (Phase 0 fix);
- (b) destroy the EGL surface/context in `nativeDetachSurface` (currently only `setRenderScale`
  tears it down → stale EGL surface after surface re-entry);
- (c) verify EGL config format matches `ANativeWindow_setBuffersGeometry(..., RGBA_8888)` and
  `holder.setFormat(PixelFormat.RGBA_8888)` in `WaylandSurfaceView`;
- (d) verify no concurrent `ANativeWindow_lock` on the same window while EGL owns it;
- (e) instrumentation: `eglQuerySurface` size log, optional 1-px `glReadPixels` probe,
  `dumpsys SurfaceFlinger` capture into evidence.

FPS exposure: `alw-host-query fps` already answers `fps=<n> res=<w>x<h> scale=<s> gpu=<id>`;
render it as a Quickshell `alw-fps` pill **and** a Waybar `custom/alw-fps` module. Gap found
during planning: `/root/.config/quickshell/alw-fps/shell.qml` is referenced by
`ConfigSynthesizer` but **never deployed by any code** — add an `AlwFpsShellGenerator` beside
`RiceShimGenerator` (`core/proot`) writing the QML into `/home/alw` and `/root` guest homes,
non-destructively (existing-file check, same invariant as `ensureDefaultConfigsExist`).

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
Run **immediately after Phase 1 is verified working on the `kms_swrast` compositor**. Goal:
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
| **AC-02** | `ALW_GLES_BLIT=1` renders visibly on the Adreno 825 (screenshot); CPU blit default unchanged and functional; both paths counted in FPS | Planned |
| **AC-03** | `alw-host-query fps` returns live `fps/res/scale/gpu`; FPS pill visible on the Sekai Pop bar/dock; value tracks the measured rate (±1) | Planned |
| **AC-04** | `alw-host-query scale set 0.5` (and the HUD cycler) resizes the Hyprland desktop live — `wl_output.mode` + `xdg_toplevel.configure` re-emitted, no compositor restart (screenshots at 0.5× and 1.0×) | Planned |
| **AC-05** | `eglinfo -p wayland` in a guest app reports zink (Turnip Adreno (TM) 825); zero llvmpipe vendor strings in the session | Planned |
| **AC-06** | Compositor-zink spike executed (max 2 attempts, right after Phase 1); outcome documented — enabled on clean boot, off-by-default with the hybrid split preserved on deep blockers; always revertible in one profile flip | Planned |
| **AC-07** | Status/navigation bars hidden in session; swipe shows transient bars (screenshot) | Planned |
| **AC-08** | HUD pill draggable + collapsible; position persists across session restarts | Planned |
| **AC-09** | Kitty launches fish with the imported CachyOS fish config and fastfetch greeting; bash fallback when fish is missing | Planned |
| **AC-10** | Unit tests + `./gradlew assembleDebug` green; evidence in `docs/tasks/TASK-026/evidence/`; commits pushed to `origin/main` | Planned (Phase 0 committed) |

## 5. Verification Plan

1. Phase 1: `ALW_GLES_BLIT=1` session, screenshot vs CPU path, `dumpsys SurfaceFlinger`,
   frame-cadence log.
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
