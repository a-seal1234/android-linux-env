# TASK-012 Verification Evidence — app-ui First-Launch Wizard, Automated Installer & Workstation Dashboard

**Epic:** [EPIC-ALW-P3 — Phase 3](../../../initiatives/android-linux-workstation/epics/phase-3-onboarding-ui-integrations/EPIC.md)  
**Task:** [TASK-012](../TASK.md)  
**Date:** 2026-11-14  
**Author:** Codex / local Linux environment  

---

## 1. Overview of Delivered Subsystems

### 1.1 Multi-Step First-Launch Setup Wizard
- Implemented `WizardViewModel` and `WizardScreen` in `com.alw.workstation.ui.wizard`:
  - **Step 1 (Distro & Preset Selection):**
    - Configures base rootfs source (Default Arch Linux ARM aarch64 template or local archive import).
    - Presents curated presets:
      - *Creative Workstation (Recommended):* Hyprland Wayland compositor + zwp_tablet_tool_v2 pen engine active. Tuned for Krita, Blender, Inkscape on 144Hz panel.
      - *Minimal Developer:* Lightweight Hyprland + Kitty terminal environment with high-speed UFS build cache redirection.
      - *Safe Mode:* Failsafe LabWC stacking compositor with CPU software rasterization (`llvmpipe`).
    - Configurable Linux username (default: "alw") with strict input sanitization.
    - HiDPI display scale factor slider (1.0x to 2.5x, defaulting to 1.75x for Xiaomi Pad 8 3200x2136 panel).
  - **Step 2 (GPU Driver Store Setup):**
    - Queries curated Mesa 25.0+ Adreno 825 KGSL driver releases via `GitHubReleaseFetcher` with SHA256 checksum verification.
    - Inspects ELF ABI compatibility via `ElfAbiValidator` to guarantee `GLIBC_AARCH64` runtime compatibility and reject Android Bionic `.so` packages.
    - Supports local driver archive import or one-tap skip to `SOFTWARE_FALLBACK` (`llvmpipe`).
  - **Step 3 (Live Automated Provisioning):**
    - Real-time step milestone tracking (Steps 1–7) with a deterministic progress bar.
    - Expandable dark-mode terminal log view streaming pacman keyring setup, package installations, user creation, AUR makepkg wrapper, and low-latency audio bridge configuration.
    - Error and retry handling with clear failure notices.

### 1.2 Workstation Dashboard & Workspace Manager
- Implemented `DashboardViewModel` and `DashboardScreen` in `com.alw.workstation.ui.dashboard`:
  - **Quick Launch Hero Card:** Surfaces the active profile (Hyprland / LabWC, scale factor, resolution, active Turnip GPU driver) with one-tap workstation launch.
  - **Workspace Manager:**
    - Lists provisioned rootfs instances (`RootfsManager.listRootfs()`) with storage size in MB, shell validity, and provisioning status.
    - Provides interactive dialogs for workspace creation, snapshot cloning, and deletion.
  - **Stack Profiles Section:** Lists installed profiles with one-tap switching.

### 1.3 Active Desktop Session Surface & Floating Control Pill
- Implemented `SessionSurfaceScreen` and `FloatingControlPill` in `com.alw.workstation.ui.session`:
  - Full-screen `AndroidView` container hosting `WaylandSurfaceView` wired to `SessionController`, `CompositorOrchestrator`, `AudioBridgeManager`, and `InputDispatcher`.
  - **Floating Control Pill Overlay:**
    - Non-intrusive semi-transparent pill overlaying the active session in the top-right corner.
    - Collapsed state: displays input mode indicator (`PEN` vs `RAW`) and live audio latency (`ALW · PEN · 5.0ms`).
    - Expanded sheet:
      - Live toggle between `CREATIVE_PEN` (Krita/Blender pen display) and `GAMING_RAW` (1000Hz relative mouse capture).
      - Audio status (48kHz stereo, latency, volume slider).
      - Safe Mode alert banner (if `SessionState.SafeModeFallback`).
      - Clean session disconnect / termination button.

---

## 2. Test Verification Results

### 2.1 Unit Tests in `:app` (`./gradlew :app:test`)
Executed `./gradlew :app:test`:
```text
> Task :app:testDebugUnitTest
> Task :app:testReleaseUnitTest
> Task :app:test

BUILD SUCCESSFUL in 1s
10 actionable tasks: 4 executed, 6 up-to-date
```
Tests verified:
- `WizardViewModelTest`:
  - `testInitialStateAndPresets`: Verified default state, preset selection, username sanitization, and scale factor clamping.
  - `testStepNavigation`: Verified bidirectional navigation between Step 1, Step 2, and Step 3.
  - `testDriverSelectionAndValidation`: Verified release selection, SHA256 checksum matching, software fallback, and missing file rejection.
  - `testBuildTargetProfile`: Verified correct generation of `StackProfile` models for Creative, Minimal Dev, and Safe Mode configurations.
  - `testProvisioningStateProgression`: Verified progression across all 7 steps, log line streaming, and transition to `WizardStep.COMPLETED`.
- `DashboardViewModelTest`:
  - `testInitialDashboardState`: Verified workspace loading, active profile resolution, and initial `Idle` state.
  - `testWorkspaceManagement`: Verified workspace creation, cloning, and deletion workflows.
  - `testProfileAndInputSelection`: Verified profile switching and `CREATIVE_PEN` vs `GAMING_RAW` input mode toggles.
  - `testSessionLaunchAndTeardown`: Verified clean transition into `SessionState.Running` and teardown to `SessionState.Stopped`.
  - `testDialogStateToggles`: Verified creation, clone, and delete dialog visibility state.

### 2.2 Full Workspace Test Suite (`./gradlew test`)
Executed `./gradlew test`:
```text
BUILD SUCCESSFUL in 3s
159 actionable tasks: 12 executed, 147 up-to-date
```
All unit tests across all 4 modules (`:app`, `:core:session`, `:core:proot`, `:bridge:wayland`) passed.

### 2.3 Assemble Debug Build (`./gradlew assembleDebug`)
Executed `./gradlew assembleDebug`:
```text
BUILD SUCCESSFUL in 919ms
128 actionable tasks: 8 executed, 120 up-to-date
```
APK assembled with 0 compilation errors and 0 warnings.
