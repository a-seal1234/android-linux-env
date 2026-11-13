# Workstation Backlog & Task Sequencing

**Initiative:** [Android Linux Workstation](INITIATIVE.md)  
**Central Task Board:** [TASKS.md](../../../TASKS.md)  

---

## 1. Task Dependency Graph

```mermaid
flowchart TD
    T001["TASK-001: Architecture & Docs Baseline (Phase 0)"]
    
    subgraph Phase 1: Core Runtime & PRoot
        T002["TASK-002: Gradle & Multi-Module Setup"]
        T003["TASK-003: StackProfile & Session Core Engine"]
        T004["TASK-004: PRoot Harness & Multi-Rootfs Mgr"]
        T005["TASK-005: Arch Linux Auto-Provisioning"]
        T006["TASK-006: Storage Bridge & Cache Redir"]
    end
    
    subgraph Phase 2: Wayland Bridge & GPU
        T007["TASK-007: Native Wayland Server & Surface"]
        T008["TASK-008: Pointer Capture & Scancode Map"]
        T009["TASK-009: Turnip Adreno 825 KGSL Driver"]
        T010["TASK-010: Hyprland Session & Safe Mode"]
        T011["TASK-011: PipeWire Audio Socket Bridge"]
    end
    
    subgraph Phase 3: UI & Integrations
        T012["TASK-012: Compose One-Tap Onboarding Wizard"]
        T013["TASK-013: Stack Configurator & Switcher"]
        T014["TASK-014: Shizuku Companion & Phantom Fix"]
        T015["TASK-015: Adreno KGSL Telemetry HUD"]
        T016["TASK-016: x86_64 FEX/box64 Translation Hook"]
    end
    
    subgraph Phase 4: Hardening & RC1
        T017["TASK-017: Xiaomi Pad 8 144Hz Benchmarking"]
        T018["TASK-018: High-Concurrency Build Stress"]
        T019["TASK-019: RC1 Candidate & Release Notes"]
    end

    T001 --> T002
    T002 --> T003
    T002 --> T004
    T003 --> T005
    T004 --> T005
    T004 --> T006
    
    T005 --> T007
    T007 --> T008
    T007 --> T009
    T009 --> T010
    T007 --> T011
    
    T010 --> T012
    T010 --> T013
    T003 --> T014
    T014 --> T015
    T004 --> T016
    
    T012 --> T017
    T013 --> T017
    T015 --> T017
    T016 --> T018
    T017 --> T019
    T018 --> T019
```

---

## 2. Sequenced Task Inventory

| ID | Title | Epic | Risk | Prerequisites |
|---|---|---|---|---|
| **TASK-001** | Architecture Baseline, Docs Hierarchy & ADRs | Phase 0 | Standard | None |
| **TASK-002** | Scaffolding, Multi-Module Gradle & Toolchain | Phase 1 | Standard | TASK-001 |
| **TASK-003** | `session-core` State Machine & `StackProfile` Engine | Phase 1 | Standard | TASK-002 |
| **TASK-004** | `proot-runtime` PRoot Harness & Multi-Rootfs Manager | Phase 1 | High Risk | TASK-002 |
| **TASK-005** | Arch Linux ARM Automated Provisioning Script | Phase 1 | Standard | TASK-003, TASK-004 |
| **TASK-006** | Selective Storage Bridge & Build Cache Redirection | Phase 1 | High Risk | TASK-004 |
| **TASK-007** | `wayland-bridge` Native C/Rust Surface Server | Phase 2 | Critical | TASK-005 |
| **TASK-008** | Pointer Capture & Hardware Scancode Mapping (`caps:super`) | Phase 2 | High Risk | TASK-007 |
| **TASK-009** | Turnip Adreno 825 KGSL Driver Manager & Node Binding | Phase 2 | Critical | TASK-007 |
| **TASK-010** | Compositor Orchestration: Hyprland & Safe Mode | Phase 2 | High Risk | TASK-009 |
| **TASK-011** | Low-Latency PipeWire / PulseAudio Socket Bridge | Phase 2 | Standard | TASK-007 |
| **TASK-012** | `app-ui` First-Launch Wizard & Automated Installer | Phase 3 | Standard | TASK-010 |
| **TASK-013** | Stack Configurator UI & Live Compositor Switcher | Phase 3 | Standard | TASK-010 |
| **TASK-014** | Shizuku Companion (`UID 2000`) & Phantom Process Manager | Phase 3 | High Risk | TASK-003 |
| **TASK-015** | Real-Time Adreno KGSL GPU Telemetry HUD Overlay | Phase 3 | Standard | TASK-014 |
| **TASK-016** | Optional x86_64 FEX/box64 Translation Hook (`-q`) | Phase 3 | High Risk | TASK-004 |
| **TASK-017** | Xiaomi Pad 8 Hardware Hardening & 144Hz Benchmarks | Phase 4 | High Risk | TASK-012, TASK-013, TASK-015 |
| **TASK-018** | High-Concurrency Build Stress & Thermal Throttling Test | Phase 4 | High Risk | TASK-016 |
| **TASK-019** | RC1 Candidate Packaging, Security Audit & Release | Phase 4 | Critical | TASK-017, TASK-018 |
