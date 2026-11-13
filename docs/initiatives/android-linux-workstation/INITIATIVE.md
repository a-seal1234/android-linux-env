# Initiative: Android Linux Workstation (ALW)

**Type:** Initiative & Architecture Index; not a workflow-status record.  
**Source:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md)  
**Central Task Board:** [TASKS.md](../../../TASKS.md) is the authoritative source for task status.  

---

## 1. Goal

Turn unrooted Android tablets—specifically targeting the **Xiaomi Pad 8** (Snapdragon 8s Gen 4, Adreno 825, HyperOS 2.0)—into a desktop-grade Linux development workstation running **Arch Linux ARM (`aarch64`)** with a native embedded Wayland desktop environment (Hyprland / LabWC).

The system features:
1. Zero-terminal one-tap onboarding.
2. Profile-driven, non-destructive runtime injection (`StackProfile`).
3. Direct Adreno 825 hardware acceleration via Mesa Turnip and `/dev/kgsl-3d0`.
4. Native Wayland display bridge with pointer capture and hardware keyboard remapping.
5. Selective storage bind mounts and UFS build cache redirection.
6. Optional x86_64 FEX/box64 translation and Shizuku lifecycle companion.

---

## 2. Phase Epics

| Epic | Phase | Focus | Folder | Dependency |
|---|---|---|---|---|
| [EPIC-ALW-P0](epics/phase-0-architecture-planning/EPIC.md) | Phase 0 | Architecture Baseline, Docs Hierarchy & ADRs | `epics/phase-0-architecture-planning/` | None |
| [EPIC-ALW-P1](epics/phase-1-core-runtime-proot/EPIC.md) | Phase 1 | Core Runtime, Multi-Rootfs & PRoot Engine | `epics/phase-1-core-runtime-proot/` | Phase 0 Exit |
| [EPIC-ALW-P2](epics/phase-2-wayland-turnip-compositor/EPIC.md) | Phase 2 | Native Wayland Bridge & Turnip KGSL GPU Stack | `epics/phase-2-wayland-turnip-compositor/` | Phase 1 Exit |
| [EPIC-ALW-P3](epics/phase-3-onboarding-ui-integrations/EPIC.md) | Phase 3 | Onboarding UI, Stack Configurator & Shizuku | `epics/phase-3-onboarding-ui-integrations/` | Phase 2 Exit |
| [EPIC-ALW-P4](epics/phase-4-benchmarking-hardening/EPIC.md) | Phase 4 | Hardware Hardening, Benchmarking & Production | `epics/phase-4-benchmarking-hardening/` | Phase 3 Exit |

---

## 3. Backlog & Traceability Links

- [Backlog & Sequencing](BACKLOG.md): Full dependency sequencing of planned tasks across all phases.
- [Requirements Traceability Matrix](REQUIREMENTS.md): Mapping from SRS requirement IDs to tasks.
- [Architectural Decision Records (ADRs)](../../tasks/TASK-001/DECISION.md): Baseline architectural decisions.
