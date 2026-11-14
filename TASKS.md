# Shared task board

**TASK-003 session-core state machine and StackProfile engine completed:** Codex; local Linux environment; branch `main`. Implemented full two-way `StackProfile` JSON deserialization/serialization matching schema 1.0.0, `ProfileValidator` enforcing Non-Destructive Invariant and Scoped Storage safety, `ProfileRepository` with atomic file persistence, `EnvironmentGenerator` with Turnip KGSL/Adreno proprietary Zink/LLVMpipe and build cache redirection, `HyprlandIpcClient` UNIX domain socket client, and `SessionController` reactive state machine. All 20 unit tests pass; full `./gradlew test` and `./gradlew assembleDebug` pass with exit code 0. [TASK-003](docs/tasks/TASK-003/TASK.md); [evidence](docs/tasks/TASK-003/evidence/verification.md).

**TASK-002 multi-module Gradle scaffolding and build setup completed:** Codex; local Linux environment; branch `main`. Scaffolded the 4-module Gradle build (`:app`, `:core:session`, `:core:proot`, `:bridge:wayland`) targeting Android SDK 35, NDK r27c (`27.3.13750724`), JDK 17, and Kotlin 2.1.0/Compose. Integrated architectural refinements: adapted the embedded Wayland/audio bridge architecture from `Beauty114514/trierarch-app`, added Adreno 825 (A8xx) driver flexibility (Turnip `.tar.gz` and Qualcomm proprietary `libvulkan_adreno.so` + Zink, alongside `llvmpipe` Safe Mode), and enforced workspace hygiene (.gitignore/.antigravityignore). Full `./gradlew assembleDebug` and `./gradlew test` pass with 5 unit tests; generated `app-debug.apk` packages `libwayland_bridge.so` for `arm64-v8a`. [TASK-002](docs/tasks/TASK-002/TASK.md); [evidence](docs/tasks/TASK-002/evidence/verification.md).

**Claimed TASK-001 architecture and documentation baseline completed:** Codex; local Linux environment; branch `main`. Inspected the reference project documentation hierarchy and conventions from `/media/DATA/project/perfectnote` (Dev Framework v0.2). Replicated the exact documentation structure inside `docs/`, authored the complete Software Requirements Specification (`ANDROID-LINUX-WORKSTATION-SRS-v0.1.md`), comprehensive system architecture documents (`ARCHITECTURE.md`, `STACK-PROFILE-SCHEMA.md`, `WAYLAND-BRIDGE-DESIGN.md`, `PROOT-RUNTIME-DESIGN.md`, `TURNIP-KGSL-INTEGRATION.md`, `SHIZUKU-LIFECYCLE-SERVICE.md`), Architectural Decision Records (ADR-001 through ADR-009 in `DECISION.md`), sequenced the 19-task backlog, established the requirements traceability matrix, and verified local toolchains (Android SDK 35/36, NDK r27c, JDK 17, Rust 1.98.1, CMake, Ninja). Approved by user on 2026-11-13. [TASK-001](docs/tasks/TASK-001/TASK.md); [DECISION.md](docs/tasks/TASK-001/DECISION.md); [verification](docs/tasks/TASK-001/evidence/verification.md).

---

This root file is the repository’s only workflow-status source. Task scope, acceptance criteria, planning, actual changes, detailed evidence and handoffs live in each linked `docs/tasks/<TASK-ID>/TASK.md`. This board adheres to [Dev Framework v0.2](docs/dev-framework/DEV-FRAMEWORK-v0.2.md).

**Last board review:** 2026-11-13

---

## RC1 plan (proposed)

| Phase | Order | Task | Reason |
|---|---|---|---|
| Before RC1 | 1 | TASK-001 — Architecture baseline & ADRs | Foundational architecture and toolchain baseline. |
| Before RC1 | 2 | TASK-002 — Project scaffolding & Gradle multi-module | Multi-module build setup (`app-ui`, `session-core`, `proot-runtime`, `wayland-bridge`). |
| Before RC1 | 3 | TASK-003 — `session-core` & `StackProfile` engine | Non-destructive runtime injection and state management. |
| Before RC1 | 4 | TASK-004 — `proot-runtime` & multi-rootfs manager | Unrooted Linux container and directory isolation. |
| Before RC1 | 5 | TASK-005 — Arch Linux ARM automated provisioning | Zero-terminal pacman, core packages, and global flags. |
| Before RC1 | 6 | TASK-006 — Storage bridge & build cache redirection | High-speed UFS compilation and Scoped Storage protection. |
| Before RC1 | 7 | TASK-007 — `wayland-bridge` native surface server | In-process native Wayland compositor on Android `ANativeWindow`. |
| Before RC1 | 8 | TASK-008 — Pointer capture & keyboard remapping (`caps:super`) | Desktop-grade relative mouse and HyperOS Super key bypass. |
| Before RC1 | 9 | TASK-009 — Turnip Adreno 825 KGSL driver manager | Direct Vulkan acceleration via `/dev/kgsl-3d0`. |
| Before RC1 | 10 | TASK-010 — Compositor orchestration & Safe Mode | Hyprland session config generator and LabWC Safe Mode. |
| Before RC1 | 11 | TASK-011 — Low-latency PipeWire audio bridge | Native UNIX socket audio routing to Android `AAudio`/Oboe. |
| Before RC1 | 12 | TASK-012 — One-tap onboarding wizard UI | First-launch three-tap wizard in Jetpack Compose. |
| Before RC1 | 13 | TASK-013 — Stack configurator & live switcher | Interactive settings editor and `hyprctl` live IPC. |
| Before RC1 | 14 | TASK-014 — Shizuku companion & phantom process fix | Disables Android 12+ phantom process killer (`max_phantom_processes`). |
| Before RC1 | 15 | TASK-015 — Adreno KGSL GPU telemetry HUD | Real-time GPU frequency, thermals, and memory overlay. |
| Before RC1 | 16 | TASK-016 — Transparent x86_64 FEX hook (`-q`) | Foreign binary translation with zero aarch64 overhead. |
| Before RC1 | 17 | TASK-017 — Xiaomi Pad 8 hardware hardening & 144Hz | Target device validation, input latency, and display tuning. |
| Before RC1 | 18 | TASK-018 — High-concurrency compilation stress test | 32+ thread compilation and thermal stability verification. |
| Before RC1 | 19 | TASK-019 — RC1 candidate packaging & release audit | Signed release APK, security review, and release documentation. |

### RC1 exit gate
- All tasks in the **Before RC1** table are marked **Done** with revision-bound evidence.
- Tested on Xiaomi Pad 8 (Snapdragon 8s Gen 4, Adreno 825, HyperOS 2.0).
- Automated provisioning completes to working desktop without terminal intervention.
- Zero-terminal launch into Hyprland and LabWC Safe Mode verified.
- Direct Turnip KGSL Vulkan rendering confirmed at 144Hz with zero frame drops.
- Rust/C++ multi-thread compilation completes without phantom process termination.

---

## Status meanings

Planned · Ready · In Progress · In Review · Blocked · Done · Deferred. Status is recorded here only; task detail files do not mirror it. A gate or evidence result does not change workflow status by itself.

---

## Initiative: Android Linux Workstation (ALW)

See the [Initiative index](docs/initiatives/android-linux-workstation/INITIATIVE.md) and [SRS v0.1](docs/srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md). See the [Backlog](docs/initiatives/android-linux-workstation/BACKLOG.md) and [Requirements Traceability Matrix](docs/initiatives/android-linux-workstation/REQUIREMENTS.md).

### Phase Epic folders

| Epic | Focus | Epic record | Gate |
|---|---|---|---|
| EPIC-ALW-P0 | Architecture Baseline, Docs Hierarchy & ADRs | [EPIC.md](docs/initiatives/android-linux-workstation/epics/phase-0-architecture-planning/EPIC.md) | None |
| EPIC-ALW-P1 | Core Runtime, Multi-Rootfs & PRoot Engine | [EPIC.md](docs/initiatives/android-linux-workstation/epics/phase-1-core-runtime-proot/EPIC.md) | Phase 0 exit review |
| EPIC-ALW-P2 | Native Wayland Bridge & Turnip KGSL GPU Stack | [EPIC.md](docs/initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md) | Phase 1 exit review |
| EPIC-ALW-P3 | Onboarding UI, Stack Configurator & Shizuku | [EPIC.md](docs/initiatives/android-linux-workstation/epics/phase-3-onboarding-ui-integrations/EPIC.md) | Phase 2 exit review |
| EPIC-ALW-P4 | Hardware Hardening, Benchmarking & Production | [EPIC.md](docs/initiatives/android-linux-workstation/epics/phase-4-benchmarking-hardening/EPIC.md) | Phase 3 exit review |

---

## Tasks

| ID | Task | Status | Epic | Owner / Harness | Risk | Dependencies / Gates |
|---|---|---|---|---|---|---|
| TASK-001 | [Architecture Baseline, Docs Hierarchy & ADRs](docs/tasks/TASK-001/TASK.md) | Done | [EPIC-ALW-P0 — Phase 0](docs/initiatives/android-linux-workstation/epics/phase-0-architecture-planning/EPIC.md) | Codex / local Linux | Standard | Complete and approved. [Decision](docs/tasks/TASK-001/DECISION.md); [evidence](docs/tasks/TASK-001/evidence/verification.md). |
| TASK-002 | [Scaffolding, Multi-Module Gradle & Toolchain](docs/tasks/TASK-002/TASK.md) | Done | [EPIC-ALW-P1 — Phase 1](docs/initiatives/android-linux-workstation/epics/phase-1-core-runtime-proot/EPIC.md) | Codex / local Linux | Standard | Complete. 4 modules assemble cleanly; 5 unit tests pass. [Evidence](docs/tasks/TASK-002/evidence/verification.md). |
| TASK-003 | [`session-core` State Machine & `StackProfile` Engine](docs/tasks/TASK-003/TASK.md) | Done | [EPIC-ALW-P1 — Phase 1](docs/initiatives/android-linux-workstation/epics/phase-1-core-runtime-proot/EPIC.md) | Codex / local Linux | Standard | Complete. 20 unit tests pass. [Evidence](docs/tasks/TASK-003/evidence/verification.md). |
| TASK-004 | [`proot-runtime` PRoot Harness & Multi-Rootfs Manager](docs/tasks/TASK-004/TASK.md) | Planned | [EPIC-ALW-P1 — Phase 1](docs/initiatives/android-linux-workstation/epics/phase-1-core-runtime-proot/EPIC.md) | Unassigned / local Linux | High Risk | Requires TASK-002. |
| TASK-005 | [Arch Linux ARM Automated Provisioning Script](docs/tasks/TASK-005/TASK.md) | Planned | [EPIC-ALW-P1 — Phase 1](docs/initiatives/android-linux-workstation/epics/phase-1-core-runtime-proot/EPIC.md) | Unassigned / local Linux | Standard | Requires TASK-003, TASK-004. |
| TASK-006 | [Selective Storage Bridge & Build Cache Redirection](docs/tasks/TASK-006/TASK.md) | Planned | [EPIC-ALW-P1 — Phase 1](docs/initiatives/android-linux-workstation/epics/phase-1-core-runtime-proot/EPIC.md) | Unassigned / local Linux | High Risk | Requires TASK-004. |
| TASK-007 | [`wayland-bridge` Native C/Rust Surface Server](docs/tasks/TASK-007/TASK.md) | Planned | [EPIC-ALW-P2 — Phase 2](docs/initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md) | Unassigned / local Linux | Critical | Requires TASK-005. |
| TASK-008 | [Pointer Capture & Hardware Scancode Mapping (`caps:super`)](docs/tasks/TASK-008/TASK.md) | Planned | [EPIC-ALW-P2 — Phase 2](docs/initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md) | Unassigned / local Linux | High Risk | Requires TASK-007. |
| TASK-009 | [Turnip Adreno 825 KGSL Driver Manager & Node Binding](docs/tasks/TASK-009/TASK.md) | Planned | [EPIC-ALW-P2 — Phase 2](docs/initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md) | Unassigned / local Linux | Critical | Requires TASK-007. |
| TASK-010 | [Compositor Orchestration: Hyprland & Safe Mode](docs/tasks/TASK-010/TASK.md) | Planned | [EPIC-ALW-P2 — Phase 2](docs/initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md) | Unassigned / local Linux | High Risk | Requires TASK-009. |
| TASK-011 | [Low-Latency PipeWire / PulseAudio Socket Bridge](docs/tasks/TASK-011/TASK.md) | Planned | [EPIC-ALW-P2 — Phase 2](docs/initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md) | Unassigned / local Linux | Standard | Requires TASK-007. |
| TASK-012 | [`app-ui` First-Launch Wizard & Automated Installer](docs/tasks/TASK-012/TASK.md) | Planned | [EPIC-ALW-P3 — Phase 3](docs/initiatives/android-linux-workstation/epics/phase-3-onboarding-ui-integrations/EPIC.md) | Unassigned / local Linux | Standard | Requires TASK-010. |
| TASK-013 | [Stack Configurator UI & Live Compositor Switcher](docs/tasks/TASK-013/TASK.md) | Planned | [EPIC-ALW-P3 — Phase 3](docs/initiatives/android-linux-workstation/epics/phase-3-onboarding-ui-integrations/EPIC.md) | Unassigned / local Linux | Standard | Requires TASK-010. |
| TASK-014 | [Shizuku Companion (`UID 2000`) & Phantom Process Manager](docs/tasks/TASK-014/TASK.md) | Planned | [EPIC-ALW-P3 — Phase 3](docs/initiatives/android-linux-workstation/epics/phase-3-onboarding-ui-integrations/EPIC.md) | Unassigned / local Linux | High Risk | Requires TASK-003. |
| TASK-015 | [Real-Time Adreno KGSL GPU Telemetry HUD Overlay](docs/tasks/TASK-015/TASK.md) | Planned | [EPIC-ALW-P3 — Phase 3](docs/initiatives/android-linux-workstation/epics/phase-3-onboarding-ui-integrations/EPIC.md) | Unassigned / local Linux | Standard | Requires TASK-014. |
| TASK-016 | [Optional x86_64 FEX/box64 Translation Hook (`-q`)](docs/tasks/TASK-016/TASK.md) | Planned | [EPIC-ALW-P3 — Phase 3](docs/initiatives/android-linux-workstation/epics/phase-3-onboarding-ui-integrations/EPIC.md) | Unassigned / local Linux | High Risk | Requires TASK-004. |
| TASK-017 | [Xiaomi Pad 8 Hardware Hardening & 144Hz Benchmarks](docs/tasks/TASK-017/TASK.md) | Planned | [EPIC-ALW-P4 — Phase 4](docs/initiatives/android-linux-workstation/epics/phase-4-benchmarking-hardening/EPIC.md) | Unassigned / local Linux | High Risk | Requires TASK-012, TASK-013, TASK-015. |
| TASK-018 | [High-Concurrency Build Stress & Thermal Throttling Test](docs/tasks/TASK-018/TASK.md) | Planned | [EPIC-ALW-P4 — Phase 4](docs/initiatives/android-linux-workstation/epics/phase-4-benchmarking-hardening/EPIC.md) | Unassigned / local Linux | High Risk | Requires TASK-016. |
| TASK-019 | [RC1 Candidate Packaging, Security Audit & Release](docs/tasks/TASK-019/TASK.md) | Planned | [EPIC-ALW-P4 — Phase 4](docs/initiatives/android-linux-workstation/epics/phase-4-benchmarking-hardening/EPIC.md) | Unassigned / local Linux | Critical | Requires TASK-017, TASK-018. |
