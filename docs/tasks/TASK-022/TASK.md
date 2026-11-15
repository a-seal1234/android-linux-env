# TASK-022 — Real-Time Adreno 825 KGSL GPU Telemetry HUD Overlay

**Epic:** [EPIC-ALW-P3 — Phase 3](../../initiatives/android-linux-workstation/epics/phase-3-onboarding-ui-integrations/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Implementation / Telemetry & UI Task  
**Board:** [Central task board](../../../TASKS.md) is the only workflow-status source.  
**Owner / harness:** Antigravity / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Risk:** Standard  
**Source:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md)  

---

## 1. Outcome

Implement a real-time hardware telemetry collector and floating HUD overlay for the Adreno 825 (Snapdragon 8s Gen 4) GPU via KGSL sysfs interfaces (`/sys/class/kgsl/kgsl-3d0/` and devfreq sysfs nodes). Display live GPU clock frequency (MHz), GPU load / busy percentage (%), temperature (°C), and memory utilization inside the floating session control HUD pill and expanded drawer in `app:ui`.

---

## 2. Source Requirements

- **REQ-GPU-002**: Real-time GPU telemetry reporting (clock speed, utilization, temperature, memory usage).
- **Adreno 825 KGSL Sysfs Integration**: Xiaomi Pad 8 Snapdragon 8s Gen 4 exposes KGSL status nodes (or devfreq governors) under `/sys/class/kgsl/kgsl-3d0/` or `/sys/class/devfreq/`. Unrooted or Shizuku-assisted queries retrieve real-time hardware telemetry without impacting rendering performance.
- **HUD UI Integration**: Surface metrics in `FloatingControlPill` and `SessionSurfaceScreen` with low refresh overhead (< 1Hz polling or reactive flow).

---

## 3. Planned Scope & Acceptance Criteria

1. **KGSL Telemetry Collector (`KgslTelemetryCollector` in `core:session` or `app`):**
   - Probe KGSL sysfs paths:
     - `/sys/class/kgsl/kgsl-3d0/gpuclk` or `cur_freq` / `clock_mhz`
     - `/sys/class/kgsl/kgsl-3d0/gpubusy` or devfreq load
     - `/sys/class/thermal/thermal_zone*/temp` (identifying GPU/SoC thermal zones)
   - Provide graceful fallback when sysfs nodes are restricted by SELinux, displaying estimated or Shizuku-queried values.
2. **HUD Overlay UI:**
   - Enhance the session HUD overlay / pill in `SessionSurfaceScreen.kt` to display `GPU: <freq>MHz · <load>% · <temp>°C`.
   - Toggleable expanded panel showing detailed hardware stats, Turnip driver version, and frame pacing.
3. **Unit Tests & On-Device Validation:**
   - Unit tests verifying metric parsing, calculations, and fallback states.
   - On-device verification on Xiaomi Pad 8 (`d370854b`) during Hyprland / Vulkan load.

---

## 4. Dependencies & Gates

- **Prerequisites:** TASK-009, TASK-012, TASK-014, TASK-021
- **Implementation Gate:** Completed.
- **Verification Gate:** Passing test suites, on-device telemetry logs and screenshot evidence.
- **Review Gate:** Complete task handoff and evidence documentation in [verification.md](evidence/verification.md). Status: **Planned**.
