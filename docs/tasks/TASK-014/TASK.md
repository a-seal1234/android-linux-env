# TASK-014 — Shizuku Companion (UID 2000) & Phantom Process Manager

**Epic:** [EPIC-ALW-P3 — Phase 3](../../initiatives/android-linux-workstation/epics/phase-3-onboarding-ui-integrations/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Implementation / Engineering Task  
**Board:** [Central task board](../../../TASKS.md) is the only workflow-status source.  
**Owner / harness:** Codex / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Risk:** High Risk  
**Source:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md)  

---

## 1. Outcome

Implement the optional Shizuku Companion (`UID 2000`) integration to bypass Android 12+ Phantom Process Killer restrictions (`max_phantom_processes 2147483647`), apply Xiaomi HyperOS PowerKeeper battery whitelisting, and report active system execution limits in the Workstation UI, maintaining 100% operation when Shizuku is absent.

---

## 2. Source Requirements

- **REQ-LIFECYCLE-003:** Shizuku Companion Integration (`UID 2000`) & PowerKeeper Fix
- **REQ-LIFECYCLE-002:** Android 12+ Phantom Process Warning & Mitigation
- **Non-Root & Optional Companion Invariant:** The workstation must remain 100% functional without Shizuku.

---

## 3. Planned Scope & Acceptance Criteria

1. **Shizuku SDK & Lifecycle Integration (`dev.rikka.shizuku`):**
   - Add Shizuku API (`dev.rikka.shizuku:api`) and Provider (`dev.rikka.shizuku:provider`) dependencies.
   - Configure `ShizukuProvider` in `AndroidManifest.xml`.
   - Implement `ShizukuCompanionManager` supporting reactive states: `NOT_INSTALLED`, `STOPPED`, `PERMISSION_REQUIRED`, `READY`.
   - Add Binder received/dead listeners and permission request result callbacks.

2. **Privileged Command Execution & Phantom Limit Bypass:**
   - Execute `/system/bin/device_config set_sync_disabled_for_tests persistent` and `/system/bin/device_config put activity_manager max_phantom_processes 2147483647`.
   - Read back the active `max_phantom_processes` value via `device_config get activity_manager max_phantom_processes`.
   - Apply HyperOS PowerKeeper whitelisting (`dumpsys deviceidle whitelist +<pkg>` and `cmd appops set <pkg> RUN_IN_BACKGROUND allow`).

3. **UI Integration:**
   - Add a compact *System & Shizuku Companion Card* on the Dashboard showing active Phantom Process limit (`32 (Default - Risk of background kill)` vs `Unlimited (Shizuku Active)`).
   - Single-tap action to request permission and apply bypass.

4. **Testing & Verification:**
   - Unit tests covering state transitions, shell command builder/execution, and fallback behavior.
   - AssembleDebug build verification.

---

## 4. Dependencies & Gates

- **Prerequisites:** TASK-003, TASK-012, TASK-013.
- **Status:** Done.
- **Evidence:** [Verification Evidence](evidence/verification.md)
