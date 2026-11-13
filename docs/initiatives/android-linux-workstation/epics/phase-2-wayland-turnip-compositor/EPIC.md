# EPIC-ALW-P2 — Phase 2 — Native Wayland Bridge & Turnip KGSL GPU Stack

**Initiative:** [Android Linux Workstation](../../INITIATIVE.md)  
**Type:** Phase Epic; groups Phase 2 deliverables. It has no workflow status.  
**Central Board:** [TASKS.md](../../../../../TASKS.md) is authoritative for child task status.  
**Risk:** Critical  
**Source:** [SRS v0.1](../../../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md)  

---

## 1. Outcome

Implement the native C/Rust Wayland server embedded into an Android `SurfaceView`, direct user-space Adreno 825 hardware Vulkan acceleration via Turnip Mesa and `/dev/kgsl-3d0`, relative hardware pointer capture (`View.requestPointerCapture()`), physical keyboard remapping (`caps:super`), compositor execution (Hyprland with `/tmp/android-session.conf` and LabWC Safe Mode), and low-latency PipeWire audio forwarding.

---

## 2. Contextual SRS Requirements

- REQ-WAYLAND-001 to REQ-WAYLAND-005 (Embedded Surface, Protocols, Pointer Capture, Keyboard, Audio)
- REQ-GPU-001 to REQ-GPU-004 (External Turnip Driver, KGSL node, Dynamic Env, Driver Modes)
- REQ-CONF-003, REQ-CONF-004, REQ-CONF-005 (Hyprland Override, IPC, Safe Mode)

---

## 3. Epic Acceptance / Exit Criteria

1. Native Wayland server initializes on `$XDG_RUNTIME_DIR/wayland-0` and renders into `ANativeWindow`.
2. Adreno 825 GPU is recognized by `vulkaninfo` via Turnip without root.
3. Hyprland launches using `/tmp/android-session.conf`, sourcing user config first and applying display overrides.
4. Relative mouse motion and physical keyboard events deliver seamlessly to Wayland clients.
5. PipeWire audio stream outputs through Android audio with < 30ms latency.
6. Safe Mode (`LabWC + llvmpipe`) boots reliably if GPU initialization fails.

---

## 4. Child Tasks

| Child Task | Outcome | Prerequisites |
|---|---|---|
| [TASK-007](../../../../tasks/TASK-007/TASK.md) | `wayland-bridge` Native C/Rust Surface Server | TASK-005 |
| [TASK-008](../../../../tasks/TASK-008/TASK.md) | Pointer Capture & Hardware Scancode Mapping (`caps:super`) | TASK-007 |
| [TASK-009](../../../../tasks/TASK-009/TASK.md) | Turnip Adreno 825 KGSL Driver Manager & Node Binding | TASK-007 |
| [TASK-010](../../../../tasks/TASK-010/TASK.md) | Compositor Orchestration: Hyprland & Safe Mode | TASK-009 |
| [TASK-011](../../../../tasks/TASK-011/TASK.md) | Low-Latency PipeWire / PulseAudio Socket Bridge | TASK-007 |
