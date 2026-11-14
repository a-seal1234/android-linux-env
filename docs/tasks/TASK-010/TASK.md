# TASK-010 — Compositor Orchestration: Hyprland & Safe Mode

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Implementation / Compositor Orchestration & Safe Mode Fallback  
**Board:** [Central task board](../../../TASKS.md) is the only workflow-status source.  
**Owner / harness:** Codex / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Risk:** High Risk (Nested Wayland backends, D-Bus session wrappers, compositor early-crash detection)  
**Source:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md) (REQ-WAYLAND-002, REQ-WAYLAND-005, REQ-COMPOSITOR-001, REQ-COMPOSITOR-002, REQ-COMPOSITOR-003, REQ-COMPOSITOR-004)

---

## 1. Outcome

Implement the comprehensive compositor orchestration engine in `core:session` and `core:proot`:
1. **Nested Wayland Backend & D-Bus Session Wrapping:**
   - In PRoot, DRM/KMS hardware access is unavailable. Inject nested Wayland backend environment flags (`AQ_BACKEND=wayland`, `WLR_BACKENDS=wayland`, `WLR_RENDERER=gles2` or `vulkan`, and `WAYLAND_DISPLAY=wayland-0`).
   - Wrap compositor launch commands with `dbus-run-session` so GTK/Qt portals, file pickers, and clipboard daemons operate without `systemd --user`.
2. **Xiaomi Pad 8 Default Config Synthesis:**
   - Automatically generate default configs if missing (`~/.config/hypr/hyprland.conf` and `~/.config/labwc/rc.xml`):
     - Display output for 3200x2136 144Hz panel with 1.75 scaling (`monitor = , preferred, auto, 1.75`) and `xwayland { force_zero_scaling = true }`.
     - Standard workstation keybindings (`SUPER + Return` for `kitty`, `SUPER + Q` to close window, `SUPER + M` to exit session).
     - Input configuration with `tablet` and `touchdevice` mappings for `zwp_tablet_tool_v2` creative workflows in Krita/Blender.
     - Automatic visual degradation under `SOFTWARE_FALLBACK` / `llvmpipe` (disabling blur and drop shadows).
3. **Automatic Safe Mode Fallback (Hyprland -> LabWC):**
   - Early-crash / crash-loop detection: if Hyprland terminates with non-zero exit code or crashes within 5 seconds of startup, automatically fall back to LabWC (`labwc -s kitty`) with software rendering (`llvmpipe`) and notify `SessionController` to prevent user black screens.

---

## 2. Planned Scope & Acceptance Criteria

- **AC-1 (Nested Wayland & D-Bus Wrapping):** Compositor launches with nested Wayland backend flags and wrapped in `dbus-run-session`.
- **AC-2 (Hyprland Config Synthesis):** `ConfigSynthesizer` generates non-destructive Hyprland configs with 144Hz HiDPI scaling, tablet input, and keybindings.
- **AC-3 (LabWC Safe Mode Config):** Generates clean Openbox-compliant `rc.xml` with lightweight window controls and low-overhead rendering.
- **AC-4 (Software Rendering Degradation):** Under `llvmpipe` / `SOFTWARE_FALLBACK`, heavy visual effects (blur, shadows) are disabled in generated config.
- **AC-5 (Crash-Loop Safe Mode Recovery):** Compositor orchestrator detects early Hyprland failure (< 5s or non-zero exit) and triggers automatic LabWC fallback.

---

## 3. Dependencies & Gates

- **Prerequisites:** TASK-009 (Completed).
- **Implementation Gate:** Passed. Nested Wayland environment, D-Bus session wrapping, config synthesizer, and CompositorOrchestrator early-crash fallback implemented.
- **Verification Gate:** Passed. All unit tests in `core:session` and `core:proot` (84 total workspace tests) and `./gradlew assembleDebug` passed.
- **Review Gate:** Passed. Evidence recorded in [evidence/verification.md](evidence/verification.md).
