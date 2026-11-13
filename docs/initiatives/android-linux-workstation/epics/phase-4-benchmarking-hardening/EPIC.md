# EPIC-ALW-P4 — Phase 4 — Hardware Hardening, Benchmarking & Production

**Initiative:** [Android Linux Workstation](../../INITIATIVE.md)  
**Type:** Phase Epic; groups Phase 4 deliverables. It has no workflow status.  
**Central Board:** [TASKS.md](../../../../../TASKS.md) is authoritative for child task status.  
**Risk:** High Risk  
**Source:** [SRS v0.1](../../../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md)  

---

## 1. Outcome

Execute hardware-specific verification and stress testing on the target **Xiaomi Pad 8** (Snapdragon 8s Gen 4, Adreno 825, HyperOS 2.0). Conduct 144Hz input/frame latency benchmarking, high-concurrency compilation stress tests (Cargo / Clang multi-thread compilation), thermal throttling characterization, and final RC1 candidate packaging.

---

## 2. Contextual SRS Requirements

- Full end-to-end verification across all SRS functional and non-functional requirements.

---

## 3. Epic Acceptance / Exit Criteria

1. Workstation sustains continuous 144Hz desktop rendering without micro-stuttering or frame drops.
2. Large-scale software compilation (`cargo build -j8` on a 50+ crate project) completes with zero phantom process terminations or OOM crashes.
3. Thermal and battery profiling confirms sustainable operation under extended load without forced OS throttling.
4. RC1 signed APK candidate builds cleanly and passes all security and release sanity checks.

---

## 4. Child Tasks

| Child Task | Outcome | Prerequisites |
|---|---|---|
| [TASK-017](../../../../tasks/TASK-017/TASK.md) | Xiaomi Pad 8 Hardware Hardening & 144Hz Benchmarks | TASK-012, TASK-013, TASK-015 |
| [TASK-018](../../../../tasks/TASK-018/TASK.md) | High-Concurrency Build Stress & Thermal Throttling Test | TASK-016 |
| [TASK-019](../../../../tasks/TASK-019/TASK.md) | RC1 Candidate Packaging, Security Audit & Release | TASK-017, TASK-018 |
