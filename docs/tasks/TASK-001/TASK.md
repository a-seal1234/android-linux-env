# TASK-001 — Architecture Baseline, Docs Hierarchy & Decision Records

**Epic:** [EPIC-ALW-P0 — Phase 0](../../initiatives/android-linux-workstation/epics/phase-0-architecture-planning/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Architecture & Documentation Baseline Task  
**Board:** [Central task board](../../../TASKS.md) is the only workflow-status source.  
**Owner / harness:** Codex / local Linux shell  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Risk:** Standard (Documentation, architecture, and governance baseline)  
**Source:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md)  

---

## 1. Outcome

Inspect the reference project documentation structure from `/media/DATA/project/perfectnote`, replicate the exact structure adhering to Dev Framework v0.2 inside `docs/`, draft comprehensive system specifications (`ARCHITECTURE.md`, `STACK-PROFILE-SCHEMA.md`, `WAYLAND-BRIDGE-DESIGN.md`, `PROOT-RUNTIME-DESIGN.md`, `TURNIP-KGSL-INTEGRATION.md`, `SHIZUKU-LIFECYCLE-SERVICE.md`), record foundational Architectural Decision Records (ADR-001 to ADR-008 in `DECISION.md`), sequence the initiative backlog, and set up the central task board.

---

## 2. Source Requirements

- All architectural requirements specified in the user request and [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md).

---

## 3. Planned Scope & Acceptance Criteria

- **AC-1:** Exact documentation structure and naming conventions from `perfectnote` replicated.
- **AC-2:** Dev Framework v0.2 specification and task template placed in `docs/dev-framework/` and `docs/templates/`.
- **AC-3:** Root workflow files (`AGENT.md`, `AGENTS.md`, `TASKS.md`, `README.md`) created and aligned.
- **AC-4:** Software Requirements Specification (SRS v0.1) authored covering the Xiaomi Pad 8 target, unrooted execution, Adreno 825 Turnip KGSL, native Wayland, and non-destructive injection.
- **AC-5:** System architecture, `StackProfile` schema, Wayland bridge, PRoot runtime, Turnip GPU, and Shizuku lifecycle designs created.
- **AC-6:** Architectural Decision Records (ADR-001 through ADR-008) authored in `DECISION.md`.
- **AC-7:** Backlog of 19 sequenced tasks and requirements traceability matrix authored.
- **AC-8:** Proposed repository layout and toolchain versions documented for user review before scaffolding code.

---

## 4. Dependencies & Gates

- **Prerequisites:** None.
- **Implementation Gate:** Complete.
- **Verification Gate:** Pass (verified via markdown link audits and git tree inspections).
- **Review Gate:** Pending user review of the Phase 0 baseline.

---

## 5. Actual Decisions & Changes

- Created root governance files: `AGENT.md`, `AGENTS.md`, `TASKS.md`, `README.md`.
- Mirrored `docs/dev-framework/DEV-FRAMEWORK-v0.2.md` and `docs/templates/TASK-TEMPLATE.md`.
- Authored `docs/srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md`.
- Authored architectural specifications in `docs/architecture/`.
- Authored initiative documents, backlog, and requirements matrix in `docs/initiatives/android-linux-workstation/`.
- Authored ADR-001 to ADR-008 in `docs/tasks/TASK-001/DECISION.md`.
- Created verification report in `docs/tasks/TASK-001/evidence/verification.md`.

---

## 6. Evidence & Handoff

- Detailed verification evidence recorded in [evidence/verification.md](evidence/verification.md).
- Status on central board: **In Review** (awaiting user approval of Phase 0 docs and toolchain proposal before Phase 1 code scaffolding).
