# TASK-012 — app-ui First-Launch Wizard, Automated Installer & Workstation Dashboard

**Epic:** [EPIC-ALW-P3 — Phase 3](../../initiatives/android-linux-workstation/epics/phase-3-onboarding-ui-integrations/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Implementation / Jetpack Compose UI & App Integration  
**Board:** [Central task board](../../../TASKS.md) is the only workflow-status source.  
**Owner / harness:** Codex / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Risk:** Standard (Compose state management, process orchestration, SurfaceView hosting)  
**Source:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md) (REQ-ONBOARD-001, REQ-CONF-001, REQ-LIFECYCLE-001)

---

## 1. Outcome

Implement the full end-to-end user experience in Jetpack Compose, directly wiring Phase 1 and Phase 2 backend engines:
1. **Multi-Step First-Launch Setup Wizard:**
   - **Step 1 (Distro & Preset Selection):** Select base rootfs (Arch Linux ARM default or local `.tar.xz`/`.tar.gz` archive import) and `StackProfile` preset (*Creative Workstation: Hyprland + Tablet/Krita/Blender*, *Minimal Dev: Hyprland + Kitty*, or *Safe Mode: LabWC*), configurable default username and HiDPI display scaling (defaulting to 1.75x for Xiaomi Pad 8).
   - **Step 2 (GPU Driver Store Setup):** Query available Mesa 25.0+ Adreno 825 KGSL driver releases via `GitHubReleaseFetcher` (or local archive import), verify SHA256 + `glibc` ELF ABI, or skip to `SOFTWARE_FALLBACK` (`llvmpipe`).
   - **Step 3 (Live Automated Provisioning):** Stream real-time step milestones (Steps 1–7) with a deterministic progress indicator, expandable live terminal log view, and error recovery/retry handling via `ArchProvisioner`.
2. **Workstation Dashboard & Active Session Surface:**
   - **Workspace Manager:** List provisioned rootfs environments (`RootfsManager`), active driver versions, and provide workspace creation, cloning, and deletion actions.
   - **Full-Screen Desktop Session Host:** Embedded Android `WaylandSurfaceView` wired to `SessionController`, `CompositorOrchestrator`, `AudioBridgeManager`, and `InputDispatcher`.
   - **Compact Collapsible Floating Control Pill:** Non-intrusive overlay toggle allowing live input profile switching (`CREATIVE_PEN` vs `GAMING_RAW`), Safe Mode fallback diagnostics, audio latency reporting, and clean session termination.

---

## 2. Planned Scope & Acceptance Criteria

- **AC-1 (Multi-Step Wizard Flow):** Wizard navigates smoothly between Distro/Profile Selection, Driver Store Setup, and Live Provisioning.
- **AC-2 (Driver Store Integration):** Lists online and local driver packages, validates ELF glibc compatibility, and binds active driver to `/opt/drivers/turnip/active/`.
- **AC-3 (Live Provisioning Telemetry):** Live terminal log stream and step progression (1–7) accurately reflect `ArchProvisioner` execution state.
- **AC-4 (Dashboard & Workspace Manager):** Lists existing rootfs workspaces with active profiles, allowing workspace switching, cloning, and deletion.
- **AC-5 (Desktop Session & Floating Pill):** Hosts `WaylandSurfaceView` in full screen with a collapsible floating pill toggle for input profile switching and session teardown.
- **AC-6 (Unit & State Tests):** Comprehensive unit tests verify Wizard state machine, Driver Store selection, Provisioning progress, and Dashboard actions.

---

## 3. Dependencies & Gates

- **Prerequisites:** TASK-010, TASK-011 (Completed).
- **Implementation Gate:** Passed (Implemented WizardViewModel, WizardScreen, DashboardViewModel, DashboardScreen, SessionSurfaceScreen, and FloatingControlPill in Jetpack Compose).
- **Verification Gate:** Passed (10 unit/UI state tests passing in :app; all unit tests passing across all modules via `./gradlew test`; APK assembled cleanly via `./gradlew assembleDebug`).
- **Review Gate:** Passed (Evidence recorded in [verification.md](evidence/verification.md)).

---

## 4. Verification Evidence

- [Verification Report](evidence/verification.md)
