# EPIC-ALW-P0 — Phase 0 — Architecture Baseline & Planning

**Initiative:** [Android Linux Workstation](../../INITIATIVE.md)  
**Type:** Phase Epic; groups Phase 0 deliverables. It has no workflow status.  
**Central Board:** [TASKS.md](../../../../../TASKS.md) is authoritative for child task status.  
**Risk:** Standard  
**Source:** [SRS v0.1](../../../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md)  

---

## 1. Outcome

Establish the foundational architecture, Dev Framework v0.2 documentation hierarchy, task board, module breakdown (`app-ui`, `session-core`, `proot-runtime`, `wayland-bridge`), `StackProfile` schema, and Architectural Decision Records (ADRs) before scaffolding source code.

---

## 2. Contextual SRS Requirements

- Architectural foundation for all requirements (REQ-ONBOARD, REQ-CONF, REQ-PROOT, REQ-GPU, REQ-WAYLAND, REQ-STORAGE, REQ-X86, REQ-LIFECYCLE).

---

## 3. Epic Acceptance / Exit Criteria

1. Documentation tree mirrors `perfectnote` conventions (Dev Framework v0.2, task board, SRS, ADRs, Epics).
2. Comprehensive system architecture document defines the 4-module decoupling and data flow.
3. `StackProfile` JSON schema is fully specified with non-destructive injection invariants.
4. Architectural Decision Records (ADR-001 through ADR-008) are drafted and linked.
5. Exact toolchain versions (Android SDK, NDK, Gradle, Kotlin, CMake, Rust) and repository directory layout are proposed for user review.

---

## 4. Child Tasks

| Child Task | Outcome | Prerequisites |
|---|---|---|
| [TASK-001](../../../../tasks/TASK-001/TASK.md) | Architecture Baseline, Docs Hierarchy & ADRs | None |
