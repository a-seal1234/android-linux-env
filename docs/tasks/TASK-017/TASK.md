# TASK-017 — Full Nested Desktop Compositor Bring-Up, Xiaomi Pad 8 144Hz Hardening & Xiaomi Pen SDK Hook

**Epic:** [EPIC-ALW-P4 — Phase 4](../../initiatives/android-linux-workstation/epics/phase-4-benchmarking-hardening/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Implementation / Engineering Task  
**Board:** [Central task board](../../../TASKS.md) is the only workflow-status source.  
**Owner / harness:** Codex / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Risk:** High Risk  
**Source:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md)  

---

## 1. Outcome

Deliver a complete nested desktop workstation experience on the physical Xiaomi Pad 8:
1. Fix PRoot PTY allocation (`/dev/pts`) so terminal emulators (`foot`, `kitty`, `weston-terminal`) spawn shells cleanly without `EACCES` / `Permission denied` when running unrooted.
2. Bring up a full nested Wayland compositor desktop (`LabWC` Safe Mode and `Hyprland`) with window decorations, titlebars, HiDPI desktop geometry (3200x2136 @ 1.75x), autostarted terminal window, and desktop background.
3. Lock display refresh rate to preferred 144Hz (`Surface.setFrameRate` / `WindowManager.LayoutParams.preferredRefreshRate`) and add frame pacing telemetry in `wayland_bridge.cpp`.
4. Implement `XiaomiPenStylusProvider` in `bridge:wayland` extending `DefaultMotionEventStylusProvider` to map Xiaomi Pen SDK vendor axes (high-precision pressure, Z-distance hover, barrel roll, side buttons) with graceful fallback to standard `MotionEvent`.

---

## 2. Planned Scope & Acceptance Criteria

1. **PTY Isolation in `ProotRunner.kt`:**
   - Remove or guard the host `/dev/pts` bind mount when running unrooted on Android.
   - PRoot emulates/intercepts pseudo-terminals cleanly without attempting host `/dev/pts` root-only access.
   - Verify `python3 -c "import pty; print(pty.openpty())"` or terminal execution inside guest PRoot.
2. **Nested Desktop Configuration:**
   - [ConfigSynthesizer.kt](../../core/session/src/main/java/com/alw/workstation/core/session/ConfigSynthesizer.kt) configures `~/.config/labwc/rc.xml` with window decorations, titlebars, themes, and `~/.config/labwc/autostart` launching terminal.
   - [CompositorOrchestrator.kt](../../core/session/src/main/java/com/alw/workstation/core/session/CompositorOrchestrator.kt) starts `labwc` or `Hyprland` with proper nested Wayland flags.
3. **144Hz Frame Pacing & Display Refresh Rate Lock:**
   - Set preferred display mode / frame rate on Android window and SurfaceView to 144Hz.
   - Add frame time and FPS telemetry in `wayland_bridge.cpp`.
4. **Xiaomi Pen SDK Hook (`XiaomiPenStylusProvider`):**
   - Implement `XiaomiPenStylusProvider` extracting vendor motion axes (`AXIS_PRESSURE`, `AXIS_DISTANCE`, `AXIS_TILT`, `AXIS_ORIENTATION`, button secondary/tertiary) with fallback.
   - Wire into `WaylandSurfaceView` for `CREATIVE_PEN` mode.
5. **Quality & Evidence:**
   - Unit tests in `core:proot`, `core:session`, and `bridge:wayland`.
   - On-device screenshot showing nested `LabWC` desktop with working window decorations and terminal.

---

## 3. Dependencies & Gates

- **Prerequisites:** TASK-010, TASK-012, TASK-013, TASK-015, TASK-016.
- **Implementation Gate:** Completed.
- **Verification Gate:** Passing test suites, clean build, verified PTY allocation inside PRoot, unit test coverage, live on-device 144Hz frame pacing logs and screenshot.
- **Review Gate:** Complete task handoff and evidence documentation in [verification.md](evidence/verification.md). Linked live screenshot: [screen_task017_desktop.png](evidence/screen_task017_desktop.png). Status: **Done**.
