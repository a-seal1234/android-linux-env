# EPIC-ALW-P3 — Phase 3 — Onboarding UI, Stack Configurator & Shizuku

**Initiative:** [Android Linux Workstation](../../INITIATIVE.md)  
**Type:** Phase Epic; groups Phase 3 deliverables. It has no workflow status.  
**Central Board:** [TASKS.md](../../../../../TASKS.md) is authoritative for child task status.  
**Risk:** Standard  
**Source:** [SRS v0.1](../../../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md)  

---

## 1. Outcome

Implement the zero-terminal One-Tap Onboarding Wizard in Jetpack Compose, the profile-driven Stack Configurator UI, Shizuku (`UID 2000`) lifecycle companion (phantom process limit lift, Xiaomi HyperOS PowerKeeper bypass), real-time Adreno KGSL GPU telemetry HUD, and transparent x86_64 FEX/box64 emulator injection.

---

## 2. Contextual SRS Requirements

- REQ-ONBOARD-001 (Wizard UI: Distro, Compositor, GPU)
- REQ-CONF-001 (StackConfigurator UI)
- REQ-LIFECYCLE-001 to REQ-LIFECYCLE-004 (Foreground Service, Phantom Warning, Shizuku Companion, GPU HUD)
- REQ-X86-001 to REQ-X86-003 (Transparent x86_64 FEX Hook)

---

## 3. Epic Acceptance / Exit Criteria

1. First-Launch Wizard installs and boots to desktop with zero manual terminal commands.
2. Stack Configurator allows full customization and persists valid `StackProfile` JSON.
3. Shizuku companion successfully executes `device_config put activity_manager max_phantom_processes 2147483647` and whitelists PowerKeeper.
4. Adreno KGSL GPU telemetry HUD renders live clock frequencies and thermals over Compose viewport.
5. Foreign x86_64 binary executes via FEX while native aarch64 binary executes with zero overhead.

---

## 4. Child Tasks

| Child Task | Outcome | Prerequisites |
|---|---|---|
| [TASK-012](../../../../tasks/TASK-012/TASK.md) | `app-ui` First-Launch Wizard & Automated Installer | TASK-010 |
| [TASK-013](../../../../tasks/TASK-013/TASK.md) | Stack Configurator UI & Live Compositor Switcher | TASK-010 |
| [TASK-014](../../../../tasks/TASK-014/TASK.md) | Shizuku Companion (`UID 2000`) & Phantom Process Manager | TASK-003 |
| [TASK-015](../../../../tasks/TASK-015/TASK.md) | Real-Time Adreno KGSL GPU Telemetry HUD Overlay | TASK-014 |
| [TASK-016](../../../../tasks/TASK-016/TASK.md) | Optional x86_64 FEX/box64 Translation Hook (`-q`) | TASK-004 |
