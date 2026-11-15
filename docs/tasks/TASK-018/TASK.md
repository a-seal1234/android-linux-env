# TASK-018 — High-Concurrency 8-Core Build Stress & Thermal Test

**Epic:** [EPIC-ALW-P4 — Phase 4](../../initiatives/android-linux-workstation/epics/phase-4-benchmarking-hardening/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Verification / Stress & Thermal Benchmark Task  
**Board:** [Central task board](../../../TASKS.md) is the only workflow-status source.  
**Owner / harness:** Antigravity / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Risk:** High Risk  
**Source:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md)  

---

## 1. Outcome

Execute a comprehensive 8-core compilation stress and thermal throttling benchmark inside the unrooted Arch Linux PRoot environment on the physical Xiaomi Pad 8 (Snapdragon 8s Gen 4: 1x Cortex-X4 @ 3.0GHz, 4x Cortex-A720 @ 2.8GHz, 3x Cortex-A520 @ 2.0GHz). Validate CPU scheduling, UFS 4.0 build cache throughput, thermal curve stability, and verify that Android 12+ Phantom Process Killer does not terminate high-thread compilation processes under sustained heavy load.

---

## 2. Source Requirements

- **REQ-STRESS-001**: High-concurrency multi-thread compilation (`make -j8`/`-j16` or Rust `cargo build -j8` or parallel C/C++ compilation) without process termination.
- **REQ-THERMAL-001**: Continuous monitoring of SoC thermal zones, governor behavior, and thermal throttling under sustained 100% CPU utilization.
- **Phantom Process Immunity**: Ensure Android 16 process manager does not kill child worker processes (confirming TASK-014 Shizuku companion effectiveness).

---

## 3. Planned Scope & Acceptance Criteria

1. **Test Harness & Workload:**
   - Execute an 8-core to 16-thread intensive compilation or CPU benchmark workload inside the guest container on the Xiaomi Pad 8.
   - Monitor and record per-second CPU usage, thermals (`/sys/class/thermal/thermal_zone*/temp`), and memory consumption during the run.
2. **Thermal & Stability Verification:**
   - Run workload continuously for at least 180 seconds.
   - Confirm zero crashes, zero SIGKILL terminations, and zero unhandled PRoot panics.
   - Log initial vs peak temperatures and CPU frequency transitions.
3. **Evidence & Documentation:**
   - Record raw benchmark logs, thermal graphs/tables, and completion confirmation in `docs/tasks/TASK-018/evidence/`.

---

## 4. Dependencies & Gates

- **Prerequisites:** TASK-014, TASK-017, TASK-019, TASK-021
- **Implementation Gate:** Completed.
- **Verification Gate:** Successful sustained compilation run, thermal profile recorded, zero phantom process kills on physical Xiaomi Pad 8 (`d370854b`).
- **Review Gate:** Complete task handoff and evidence documentation in [verification.md](evidence/verification.md). Status: **Planned**.
