# TASK-035 — Quickshell (`qs`) Overlay Artifact & Layer-Surface Sync Fix

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Compositor Bridge Rendering Integrity & Guest Desktop Rice (Quickshell/Qt6)  
**Board:** [Central task board](../../../TASKS.md) is the workflow-status source.  
**Owner / harness:** Antigravity / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Write scope:** `bridge/wayland/`, `core/session/`, `core/proot/`, `docs/tasks/TASK-035/`  
**Risk:** Medium  
**Status:** **In Progress — REOPENED 2026-11-21.** The user reports the Quickshell overlay artifact is **still visible** on the current Pad 8 build, so the 2026-11-20 "artifact GONE" verdict is withdrawn and AC-05 is reopened. The padded-row repack (`presentWithGl()` tight-stride copy), LINEAR-only advertisement and `QSG_RENDER_LOOP=basic` stay in place as defence in depth but are **not** accepted as the complete fix. Next step is a fresh symptom capture (which surface, which trigger, GPU vs CPU path A/B) before any further code change.  
**Depends on:** TASK-024 (DMA-BUF pipeline), TASK-026 Phase 1 (GLES presenter, `qs -c alw-fps` pill), TASK-025 (Sekai Pop rice, `qs -c sekaipop`).  
**Verification target:** Xiaomi Pad 8, wireless ADB `192.168.51.161:44021`.  
**Alternative path investigated 2026-11-21 (freedreno/KGSL, no Vulkan layer):** the guest Mesa 27 megadriver ships freedreno and it initializes on this device — `eglinfo` with `GALLIUM_DRIVER=kgsl` reports `EGL driver name: kgsl`, renderer `Adreno (TM) 825`, OpenGL ES 3.2 and GL 4.6 core, with `/dev/kgsl-3d0` and `/usr/lib/dri/kgsl_dri.so` present. Since it bypasses Zink's WSI/Explicit-Sync fences entirely, `DriverMode.FREEDRENO_KGSL` is now implemented and selectable (`gpu.driverMode = "freedreno_kgsl"`, or per-app `ALW_GL_BACKEND=freedreno`); `ALW_GL_BACKEND=zink` stays a one-line rollback and the shipped default until an FPS/frame-time A/B is recorded. Evidence: [2026-11-21-freedreno-kgsl-probe.md](evidence/2026-11-21-freedreno-kgsl-probe.md).  
**Fallback preserved:** the CPU blit path, `alw-fps` via `debug.*` kill switches, and the Waybar fallback launch (`ConfigSynthesizer` launches Waybar when `qs` is absent) remain untouched.

---

## 1. Problem Statement

Quickshell (`qs`) surfaces — the Sekai Pop bar, its popup/launcher overlays and the `alw-fps`
pill — show rendering artifacts on the Turnip+Zink guest path: stale regions that persist after
a layer surface maps/unmaps/resizes, and frames torn by Explicit-Sync fences that the virtual
DRM node path does not honour. Two contributing layers:

| Layer | Current state | Gap |
|---|---|---|
| Host presenter (`wayland_bridge.cpp`) | `glTexImage2D` full upload per frame (line ~828) with `GL_UNPACK_ALIGNMENT = 4` (line 851) and `GL_UNPACK_ROW_LENGTH = stride/4` (line 845); **no tracking of the last-uploaded texture dimensions and no damage reset when surfaces map/unmap/resize** | Stale pixels survive a resize/unmap when the new frame reuses a differently-strided buffer |
| Guest Qt6 QML | `qs -c sekaipop` / `qs -c alw-fps` launched via `alw-gui-run` (ConfigSynthesizer lines 123/125) on Qt6/Turnip+Zink, thread render loop, with `layerrule = blur` on quickshell layers | Threaded QSG render loop + Explicit Sync fences + blur on software-backed layers artifact under the nested compositor |

## 2. Architecture

```
Guest (PRoot)                                 Host bridge (wayland_bridge.cpp)
qs -c sekaipop ─┐                             ┌─ wl_surface / (layer surfaces via Hyprland's
qs -c alw-fps  ─┼─► Hyprland 0.57 ──────────►│   recomposition) map/unmap/resize events
waybar         ─┘   Turnip+Zink (QSG)         │      │
                     │  QSG_RENDER_LOOP=basic │      ▼
                     │  (sync FBO finish,     │  resetDamage + full (0,0,srcW,srcH) upload when
                     │   no Explicit-Sync)    │  srcW/H != last-uploaded OR presenter re-init
                     └─► zwp_linux_dmabuf_v1 ──┘  OR surface map/unmap/resize; UNPACK_ALIGNMENT=4,
                                                  UNPACK_ROW_LENGTH = srcStride/4
```

Reality notes (repo facts the implementation must respect):
- The bridge's direct Wayland client is **Hyprland only**; Quickshell attaches to Hyprland, so
  layer-surface events surface at the bridge as `wl_surface` commits with size/buffer changes on
  Hyprland's side. The map/unmap/resize trigger is therefore hooked on the bridge's surface
  state transitions (map/unmap/buffer-size change), covering `zwlr_layer_surface_v1` /
  `wl_subsurface` semantics that Hyprland forwards through recomposition.
- There is no `m_texWidth` member today — Phase 1 adds last-uploaded-dimension tracking.
- `SessionManager` does not exist in this repo; session state is `SessionController` /
  `SessionState` (relevant to the sibling task TASK-036 only).

## 3. Phases

**Phase 1 — clean full-frame upload & pixel-store hardening (`bridge/wayland/src/main/cpp/wayland_bridge.cpp`).**
Track the last-uploaded texture dimensions; whenever `srcWidth != lastWidth ||
srcHeight != lastHeight`, on the first frame after `ensureGlPresenter()` (line 674) succeeds, or
whenever a tracked `wl_surface` (including layer-surface-backed and `wl_subsurface` surfaces
seen through Hyprland's commits) maps, unmaps or changes size: reset accumulated damage and
force a full `(0, 0, srcWidth, srcHeight)` texture upload for that frame. Enforce
`GL_UNPACK_ALIGNMENT = 4` and `GL_UNPACK_ROW_LENGTH = srcStride / 4` on every upload (the
existing line-845/851 calls move into the unified upload path). No CPU pixel copies introduced;
the mmap fallback path keeps its current semantics.

**Phase 2 — Qt6/Quickshell env & layer rules (`core/session/.../ConfigSynthesizer.kt`, `core/proot/.../shim/RiceShimGenerator.kt`).**
Export `QSG_RENDER_LOOP=basic` for Quickshell so Qt6 QML finishes FBO rendering synchronously
over Turnip+Zink without Explicit Sync fences — best injected inside the `alw-gui-run` shim
env block (RiceShimGenerator) so both `qs -c sekaipop` and `qs -c alw-fps` inherit it; strip /
override `layerrule = blur` targeting `quickshell` and popup layer namespaces in the synthesized
Hyprland config (ConfigSynthesizer), regardless of the blur/shadow llvmpipe-relief state.

**Phase 3 — build & device verification.** `./gradlew test assembleDebug`; manual tap-install
(HyperOS refuses `adb install` — see TASK-027 evidence); install on `192.168.51.161:44021`,
launch the session, exercise Quickshell popups / launcher overlay / the `alw-fps` pill while
windows open/close and the render scale changes; verify artifact-free presentation. Capture
evidence in `docs/tasks/TASK-035/evidence/` (screenshots + presenter log lines showing the
forced full uploads at the trigger points).

**Phase 2b — measured root cause correction (2026-11-20, after first on-device pass).** The
first implementation (LINEAR-only advertisement + `TU_DEBUG=noubwc` + `QSG_RENDER_LOOP=basic`)
did **not** remove the overlay artifact, and the log trailer disproved the UBWC hypothesis: the
mmap fallback rows were `3200x2136 stride=12800` (tight), no `rejecting non-linear modifier`
line ever fired, and forcing the CPU path (`debug.alw.gles_blit=0`) rendered the overlay
**pixel-perfect**. The actual defect is in the **GPU upload**: guests commit a padded overlay
buffer (`2400x1602 stride=9728`, i.e. 2400*4 + 128) and the Adreno ES driver does not honour
`GL_UNPACK_ROW_LENGTH`, which produced exactly the observed diagonal tile shear.
Fix: `presentWithGl()` repacks padded rows into a tight buffer before `glTexSubImage2D`
(`GL_UNPACK_ALIGNMENT=4`, `GL_UNPACK_ROW_LENGTH=0`). The LINEAR-only advertisement and the
non-linear import guard are kept as defence-in-depth.

**Phase 2c — forced 144 Hz VSync.** `dumpsys display` showed the panel pinned at mode 4 (60 Hz)
while a session ran: `preferredRefreshRate` is only a hint on HyperOS. `MainActivity` now
resolves the `supportedModes` entry matching the profile's `refreshRateHz` and sets
`preferredDisplayModeId` (mode 2 = 2136x3200@144) in the session window attributes on start and
restores mode 0 on stop; `WaylandSurfaceView.applyForcedRefreshRate()` pins the surface with
`setFrameRate(hz, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)` and feeds the native pacer the same
rate so the compositor cadence matches the panel.

**Phase 2e — freedreno linear-rendering requirement (2026-11-21, on-device).** With
`DriverMode.FREEDRENO_KGSL` the compositor renders on the Adreno 825 at native size and 60 FPS,
but the first session showed heavy vertical macro-tile shear. Root cause: the bridge cannot
import guest dma-bufs zero-copy on this device (`EGL_EXT_image_dma_buf_import=0`, and
`AHardwareBuffer_createFromHandle` rejects the virtual node's `dma_heap` handle), so it presents
via `mmap` + linear `glTexSubImage2D` — which requires the guest buffer itself to be linear.
`FD_MESA_DEBUG=notile,noubwc` only covers *internal* buffers and does not help;
`FD_MESA_DEBUG=sysmem,noubwc` does. Both `EnvironmentGenerator` (whole session) and the
`alw-gui-run` shim's `freedreno)` branch now export it. Evidence:
[2026-11-21-freedreno-sysmem-validation.md](evidence/2026-11-21-freedreno-sysmem-validation.md).
Re-opening AC-05 stays unchanged: that criterion is about the Quickshell overlay on the Zink path.

**Phase 2f — freedreno becomes the shipped default, monitor scale 1.6x (2026-11-21, on-device).**
`StackProfile.defaultArchXiaomiPad8()` now returns `FREEDRENO_KGSL` (Hyprland + KGSL, no
`mesaLoaderOverride`), and `DashboardViewModel.refresh()` prefers the first
`FREEDRENO_KGSL` profile instead of `arch-kde-plasma`; KDE/Zink profiles stay loadable and
selectable. Every profile default (and the JSON parse fallback + generated Hyprland user config)
moved `scaleFactor` 1.75 → **1.6**, which divides 3200x2136 into an exact 2000x1335 mode and
removes Hyprland's `Invalid scale passed to monitor WAYLAND-1` rounding banner on the tablet.
Evidence: [2026-11-21-default-profile-freedreno-1_6x.md](evidence/2026-11-21-default-profile-freedreno-1_6x.md).

**Phase 2d — immersive focus mode.** `SessionSurfaceScreen` hides the Android status/navigation
bars for the session lifetime (`WindowCompat.setDecorFitsSystemWindows(false)` +
`WindowInsetsControllerCompat.hide(systemBars)`, `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`) and
restores them on dispose, re-hiding after a swipe-reveal.

## 4. Acceptance Criteria

| Criterion | Description | Status |
|---|---|---|
| **AC-01** | Task registered with architecture, phases and AC table | **Done** |
| **AC-02** | Bridge forces a clean full-frame upload on dimension change, presenter re-init and surface map/unmap/resize; pixel-store state enforced on every upload | **Done** — `m_fullFrameUploadPending` + per-surface map/unmap/resize tracking; padded rows repacked to a tight stride (driver ignores `UNPACK_ROW_LENGTH`) |
| **AC-03** | `QSG_RENDER_LOOP=basic` reaches every guest `qs` instance via the `alw-gui-run` shim; verified in-guest (`/proc/<pid>/environ` or qs log) | **Done (code)** — exported in `ALW_GUI_RUN_SHIM` (`QSG_RENDER_LOOP=basic`) |
| **AC-04** | `layerrule = blur` is stripped/overridden for `quickshell` and popup layers in the synthesized session config | **Done** — `hl.layer_rule({ match = { namespace = "quickshell" }, blur = false, blur_popups = false })` + unit test |
| **AC-05** | Quickshell popups/launcher overlays and the `alw-fps` pill render artifact-free on the Pad 8 across open/close and render-scale changes (evidence) | **REOPENED 2026-11-21** — user reports the artifact is still present on the current build; the 2026-11-20 verdict is withdrawn. Prior evidence kept for reference: **Done (on-device 2026-11-20)** — overlay renders clean ([qs_overlay_after_fix.png](evidence/qs_overlay_after_fix.png), [record](evidence/on-device-verification-2026-11-20.md)); zero `rejecting non-linear modifier` lines |
| **AC-06** | Unit tests + `./gradlew assembleDebug` green; evidence in `docs/tasks/TASK-035/evidence/`; commit pushed to `origin/main` | **Done** — 231 tests / 0 failures, `assembleDebug` green |

## 5. Verification Plan

1. Unit: presenter dimension-tracking logic is native — covered by build + on-device behaviour;
   config env assertions in `RiceShimGenerator`/`ConfigSynthesizer` tests where the generators
   are already unit-tested.
2. On-device: session up → open/close qs popups rapidly, resize windows, cycle
   `alw-host-query scale set`; screenshot each state; `alw-fps` pill stable.
3. Regression: Waybar fallback rice unchanged; LabWC Safe Mode (wl_shm) unchanged.

## 6. Risks & Mitigations

- **Layer-surface events only arrive via Hyprland's recomposition** → trigger on the bridge's
  own surface map/unmap/size transitions; no protocol additions required.
- **Full uploads on every map/resize could cost FPS** → the reset is event-driven (rare
  transitions), not per-frame; normal frames keep the single full upload.
- **`QSG_RENDER_LOOP=basic` may cost Qt UI smoothness** → it affects only guest `qs`; Waybar
  fallback and guest apps unaffected; revert is one shim env line.
