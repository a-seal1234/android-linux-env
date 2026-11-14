# TASK-010 Verification Evidence — Compositor Orchestration: Hyprland & Safe Mode

**Epic:** [EPIC-ALW-P2 — Phase 2](../../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Task:** [TASK-010](../TASK.md)  
**Date:** 2026-11-14  
**Author:** Codex / local Linux environment  

---

## 1. Overview of Delivered Subsystems

### 1.1 Nested Wayland Backend & D-Bus Session Wrapping
- Injected nested Wayland environment flags into `EnvironmentGenerator`:
  - `AQ_BACKEND=wayland`: Modern Hyprland (v0.42+) Aquamarine backend for nested Wayland presentation.
  - `WLR_BACKENDS=wayland`: wlroots nested backend for LabWC.
  - `WLR_RENDERER=gles2`: GLES2 renderer matching Turnip KGSL / Zink pipeline.
  - `WAYLAND_DISPLAY=wayland-0`: Target socket bound to native in-process display server.
- Wrapped compositor launch commands with `dbus-run-session --` in `ProotRunner`:
  - Ensures GTK/Qt file pickers (e.g. Krita and Blender Open/Save dialogs) and Wayland clipboard managers function properly inside unrooted PRoot without `systemd --user`.

### 1.2 Xiaomi Pad 8 Default Config Synthesis
- Enhanced `ConfigSynthesizer`:
  - Synthesized runtime overrides for 3200x2136 144Hz panel (`monitor = WL-1, 3200x2136@144, 0x0, 1.75` and `monitor = , preferred, auto, 1.75`).
  - Added XWayland HiDPI zero scaling (`xwayland { force_zero_scaling = true }`).
  - Added workstation keybindings: `SUPER + Return` for `kitty`, `SUPER + Q` to close window, `SUPER + M` to exit session.
  - Added dedicated `tablet` and `touchdevice` input sections bound to `WL-1` for high-precision `zwp_tablet_tool_v2` creative workflows in Krita/Blender.
  - Added automatic visual degradation when running on `llvmpipe` (`SOFTWARE_FALLBACK`): disables blur, drop shadows, and sets rounding to 0.
  - Added `ensureDefaultConfigsExist` scaffolding `~/.config/hypr/hyprland.conf` and `~/.config/labwc/rc.xml` / `autostart` on first boot while preserving existing user dotfiles (Non-Destructive Invariant).

### 1.3 Automatic Safe Mode Fallback (`Hyprland` -> `LabWC`)
- Implemented `CompositorOrchestrator` in `core:session`:
  - Monitors compositor uptime and exit code against a 5-second early-crash window (`crashWindowMillis = 5000L`).
  - If Hyprland crashes (e.g. SIGSEGV 139) or exits prematurely (< 5s), automatically activates **LabWC Safe Mode** (`labwc -s kitty`) with software rasterization (`llvmpipe`).
  - Updates `SessionState` to `SafeModeFallback(originalProfile, fallbackProfile, reason)` so UI and session controllers surface the fallback clearly.
  - Avoids circular fallback loops if Safe Mode itself terminates.

---

## 2. Unit & Integration Test Results

### 2.1 Unit Tests in `core:session`
Executed `./gradlew :core:session:test`:
```text
> Task :core:session:compileDebugUnitTestKotlin
> Task :core:session:testDebugUnitTest
> Task :core:session:compileReleaseUnitTestKotlin
> Task :core:session:testReleaseUnitTest
> Task :core:session:test

BUILD SUCCESSFUL in 2s
30 actionable tasks: 6 executed, 24 up-to-date
```
Tests verified:
- `ConfigSynthesizerTest`: 6 tests verifying HiDPI monitor overrides, XWayland zero scaling, tablet/touchdevice sections, standard workstation keybindings, software fallback visual degradation, and non-destructive dotfile preservation.
- `CompositorOrchestratorTest`: 5 tests verifying normal Hyprland execution, early-crash Safe Mode fallback, non-zero exit fallback, direct LabWC launch loop prevention, and first-boot config creation.
- `EnvironmentGeneratorTest`: 3 tests verifying nested Wayland backend flags (`AQ_BACKEND`, `WLR_BACKENDS`, `WLR_RENDERER`).

### 2.2 Full Workspace Test Suite
Executed `./gradlew test`:
```text
BUILD SUCCESSFUL in 2s
153 actionable tasks: 8 executed, 145 up-to-date
```
All 84 unit tests across all 4 modules (`:app`, `:core:session`, `:core:proot`, `:bridge:wayland`) passed.

### 2.3 Assemble Debug APK
Executed `./gradlew assembleDebug`:
```text
BUILD SUCCESSFUL in 934ms
128 actionable tasks: 13 executed, 115 up-to-date
```
Clean build with exit code 0 and 0 warnings.
