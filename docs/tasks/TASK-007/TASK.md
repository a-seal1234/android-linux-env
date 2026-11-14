# TASK-007 — wayland-bridge Native Surface Server & tablet-v2 Protocol Core

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Implementation / Native Wayland Compositor Engine  
**Board:** [Central task board](../../../TASKS.md) is the only workflow-status source.  
**Owner / harness:** Codex / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Risk:** Critical (In-process native Wayland server, ANativeWindow presentation, tablet-v2 protocol)  
**Source:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md) (REQ-WAYLAND-001, REQ-WAYLAND-002)

---

## 1. Outcome

Implement the native embedded Wayland surface server in `bridge:wayland`:
1. **In-Process Wayland Display Server:**
   - Vendor and adapt the embedded Wayland compositor architecture from **`Beauty114514/trierarch-app`** (avoiding raw compositor reimplementation from scratch).
   - Exposes `wl_display`, `wl_compositor`, `wl_subcompositor`, `wl_shm` (`WL_SHM_FORMAT_XRGB8888`, `WL_SHM_FORMAT_ARGB8888`), `xdg_shell`, and `linux_dmabuf_v1`.
   - Binds UNIX domain socket at `$XDG_RUNTIME_DIR/wayland-0` with non-blocking polling and connection lifecycle management.
2. **Direct Android Surface Presentation (`ANativeWindow`):**
   - JNI bridge: `onSurfaceCreated(Surface)`, `onSurfaceChanged(width, height)`, `onSurfaceDestroyed()`.
   - Acquires `ANativeWindow` via `ANativeWindow_fromSurface`.
   - Configures buffer geometry (`WINDOW_FORMAT_RGBA_8888`) matching the native panel (3200 x 2136 @ 144Hz on Xiaomi Pad 8).
   - Synchronizes frame rendering with the display refresh cycle.
3. **Wayland `tablet-v2` Protocol Global:**
   - Exposes `zwp_tablet_manager_v2` global protocol interface, strictly separated from `wl_touch` and `wl_pointer`.
   - Initializes tablet seat bindings (`zwp_tablet_seat_v2`) and virtual tablet devices (`zwp_tablet_v2`) representing the integrated tablet digitizer.
   - Registers pen tool endpoints (`zwp_tablet_tool_v2`) for pressure-sensitive drawing and precision creative workflows.
4. **Kotlin / JNI Surface Bridge Controller:**
   - Exposes `WaylandBridgeServer` in Kotlin with lifecycle management (`startServer`, `stopServer`, `attachSurface`, `detachSurface`).

---

## 2. Planned Scope & Acceptance Criteria

- **AC-1 (Embedded Wayland Initialization):** Native server starts and binds `$XDG_RUNTIME_DIR/wayland-0` without errors.
- **AC-2 (ANativeWindow Rendering Pipeline):** `ANativeWindow_fromSurface` successfully binds to the native renderer, presents `wl_shm` guest client buffers, and safely retires buffers upon surface destruction.
- **AC-3 (Protocol Globals):** Core Wayland globals (`wl_compositor`, `wl_shm`, `xdg_wm_base`, `zwp_tablet_manager_v2`) advertise properly to connecting guest compositors and clients.
- **AC-4 (Tablet Protocol Decoupling):** `zwp_tablet_manager_v2` operates on an isolated input channel, preventing collision between touch/pointer and stylus digitizer events.
- **AC-5 (Unit & Native Verification):** Native build compiles cleanly across `arm64-v8a`; Kotlin JNI bindings unit tests pass. `./gradlew test` and `./gradlew assembleDebug` pass with 100% success.

---

## 3. Dependencies & Gates

- **Prerequisites:** TASK-005 (Done), TASK-006 (Done).
- **Implementation Gate:** In Progress.
- **Verification Gate:** Clean unit tests (`./gradlew test`) and clean build (`./gradlew assembleDebug`).
- **Review Gate:** Self-review against Dev Framework v0.2 and SRS v0.1.

---

## 4. Actual Changes & Evidence

*(To be recorded upon implementation completion)*
