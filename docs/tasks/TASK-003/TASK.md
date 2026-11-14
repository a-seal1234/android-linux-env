# TASK-003 — session-core State Machine & StackProfile Engine

**Epic:** [EPIC-ALW-P1 — Phase 1](../../initiatives/android-linux-workstation/epics/phase-1-core-runtime-proot/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Implementation / Core Orchestration & Modeling  
**Board:** [Central task board](../../../TASKS.md) is the only workflow-status source.  
**Owner / harness:** Codex / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Risk:** Standard (Core configuration model, validation, and session state machine)  
**Source:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md) (REQ-CONF-001, REQ-CONF-002, REQ-LIFECYCLE-001)  

---

## 1. Outcome

Implement the complete `session-core` orchestration engine and configuration subsystem:
1. Full two-way `StackProfile` JSON deserialization (`fromJson`) and serialization (`toJson`), matching `docs/architecture/STACK-PROFILE-SCHEMA.md`.
2. Strict profile validation (`ProfileValidator`) enforcing the Non-Destructive Invariant and parameter safety.
3. Profile repository (`ProfileRepository`) managing profile storage, retrieval, cloning, and defaults in app storage.
4. Complete session state machine (`SessionState`, `SessionController`) with reactive state transitions.
5. Non-destructive session configuration synthesizer (`ConfigSynthesizer`) generating `/tmp/android-session.conf` and safe mode scripts.
6. Centralized environment generator (`EnvironmentGenerator`) exporting runtime, cache redirection, and Turnip/Adreno driver environment variables.
7. Lightweight Hyprland IPC client (`HyprlandIpcClient`) for dynamic runtime adjustments (`hyprctl keyword`).

---

## 2. Planned Scope & Acceptance Criteria

- **AC-1:** `StackProfile` parses cleanly from arbitrary valid schema JSON and serializes back without loss.
- **AC-2:** `ProfileValidator` validates valid profiles and rejects malformed IDs, dangerous dotfile overwrite paths, or invalid scale/refresh parameters.
- **AC-3:** `ProfileRepository` safely persists profiles to JSON files, loads saved profiles, and falls back to default profiles.
- **AC-4:** `SessionController` transitions through `Idle -> Initializing -> Starting -> Running -> Stopping -> Stopped` and reports errors.
- **AC-5:** `EnvironmentGenerator` outputs exact environment maps matching the driver mode (`turnip_kgsl`, `adreno_proprietary_zink`, `llvmpipe`) and build cache redirection settings.
- **AC-6:** `ConfigSynthesizer` wraps user dotfiles first (`source = ~/.config/hypr/hyprland.conf`) before appending workstation overrides.
- **AC-7:** Comprehensive unit tests pass across all newly implemented classes with 100% test success rate.

---

## 3. Dependencies & Gates

- **Prerequisites:** TASK-002 (Done).
- **Implementation Gate:** Complete.
- **Verification Gate:** Complete (clean `./gradlew test` with 20 passing unit tests and `./gradlew assembleDebug`).
- **Review Gate:** Complete (self-review against Dev Framework v0.2).

---

## 4. Actual Changes & Evidence

- Extended `StackProfile` model with JSON deserialization (`fromJson`, `fromJsonString`), round-trip serialization, and comprehensive sub-configs.
- Created `ProfileValidator` enforcing parameter safety and the Non-Destructive Invariant (preventing dotfile overwrites).
- Created `ProfileRepository` providing atomic file persistence and default profile management.
- Created `EnvironmentGenerator` implementing driver mode exports (Turnip KGSL, proprietary Adreno + Zink, LLVMpipe) and build cache redirection.
- Created `HyprlandIpcClient` implementing UNIX socket communication for dynamic `hyprctl` keyword adjustments.
- Enhanced `SessionController` to manage the full state machine and session preparation workflow.
- Authored 20 unit tests across 5 test suites with 100% pass rate.
- Documented revision-bound verification evidence in [evidence/verification.md](evidence/verification.md).
