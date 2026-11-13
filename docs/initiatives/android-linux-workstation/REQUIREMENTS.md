# Requirements Traceability Matrix

**Initiative:** [Android Linux Workstation](INITIATIVE.md)  
**Source Specification:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md)  

---

## 1. Traceability Table

| SRS Requirement ID | Description | Primary Task | Supporting / Verification Tasks |
|---|---|---|---|
| **REQ-ONBOARD-001** | First-Launch Wizard UI (Distro, Compositor, GPU) | TASK-012 | TASK-001 |
| **REQ-ONBOARD-002** | Single-Tap Automated Provisioning & Rootfs Extract | TASK-005, TASK-012 | TASK-004 |
| **REQ-ONBOARD-003** | Pacman Keyrings & Regional Mirror Setup | TASK-005 | TASK-004 |
| **REQ-ONBOARD-004** | Core Packages (`xwayland`, `pipewire`, `yay -A`) | TASK-005 | TASK-012 |
| **REQ-ONBOARD-005** | Global Electron/Chromium Wayland & Sandbox Flags | TASK-005 | TASK-010 |
| **REQ-CONF-001** | `StackProfile` JSON Model & Schema Validation | TASK-003 | TASK-001 |
| **REQ-CONF-002** | Non-Destructive Runtime Injection Principle | TASK-003, TASK-010 | TASK-001, TASK-006 |
| **REQ-CONF-003** | Hyprland `/tmp/android-session.conf` Generator | TASK-010 | TASK-003 |
| **REQ-CONF-004** | Live Compositor IPC (`hyprctl keyword`) | TASK-010, TASK-013 | TASK-003 |
| **REQ-CONF-005** | One-Tap Safe Mode (`LabWC + llvmpipe`) | TASK-010, TASK-013 | TASK-009 |
| **REQ-PROOT-001** | Multi-Rootfs Storage (`files/rootfs/<id>`) | TASK-004 | TASK-002 |
| **REQ-PROOT-002** | Rootfs Import / Export Tarballs | TASK-004 | TASK-013 |
| **REQ-PROOT-003** | Wayland Session Discovery (`/usr/share/wayland-sessions`) | TASK-004 | TASK-013 |
| **REQ-PROOT-004** | Ptrace & Unrooted Syscall Emulation | TASK-004 | TASK-002 |
| **REQ-PROOT-005** | Soft-Symlink Emulation (`--symlink2file`) | TASK-006 | TASK-004 |
| **REQ-GPU-001** | External Mesa Turnip Driver Library Manager | TASK-009 | TASK-004 |
| **REQ-GPU-002** | Direct Kernel Node Binding (`/dev/kgsl-3d0`) | TASK-009 | TASK-004 |
| **REQ-GPU-003** | Turnip Environment Injection (`VK_ICD_FILENAMES`) | TASK-009 | TASK-003 |
| **REQ-GPU-004** | Driver Modes & Fallbacks (`kgsl`, `zink`, `llvmpipe`) | TASK-009 | TASK-010 |
| **REQ-WAYLAND-001** | Embedded Native Wayland Server on `ANativeWindow` | TASK-007 | TASK-002 |
| **REQ-WAYLAND-002** | Core Wayland Protocols (`wl_shm`, `xdg_shell`, `dmabuf`) | TASK-007 | TASK-010 |
| **REQ-WAYLAND-003** | Pointer Capture (`View.requestPointerCapture()`) | TASK-008 | TASK-007 |
| **REQ-WAYLAND-004** | Hardware Keyboard Scancode Remap (`caps:super`) | TASK-008 | TASK-010 |
| **REQ-WAYLAND-005** | Low-Latency PipeWire / PulseAudio Socket Bridge | TASK-011 | TASK-007 |
| **REQ-STORAGE-001** | Selective `/mnt/sdcard` Bind Mount | TASK-006 | TASK-004 |
| **REQ-STORAGE-002** | `/storage/emulated/0/Android/data` Exclusion | TASK-006 | TASK-004 |
| **REQ-STORAGE-003** | High-Speed UFS Build Cache Redirection | TASK-006 | TASK-003 |
| **REQ-X86-001** | Transparent PRoot `-q` Emulation Hook | TASK-016 | TASK-004 |
| **REQ-X86-002** | Zero Overhead on Native `aarch64` Binaries | TASK-016 | TASK-018 |
| **REQ-X86-003** | Vulkan & GL Thunk Library Mounts | TASK-016 | TASK-009 |
| **REQ-LIFECYCLE-001**| Foreground Service & `PARTIAL_WAKE_LOCK` | TASK-003 | TASK-014 |
| **REQ-LIFECYCLE-002**| Phantom Process Killer Warning & Detection | TASK-014 | TASK-003 |
| **REQ-LIFECYCLE-003**| Shizuku Companion (`UID 2000`) & PowerKeeper Fix | TASK-014 | TASK-018 |
| **REQ-LIFECYCLE-004**| Real-Time Adreno KGSL GPU Telemetry HUD | TASK-015 | TASK-014, TASK-017 |
