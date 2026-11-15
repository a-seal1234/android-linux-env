# Hardware Smoke Test Report (Round 2) — Xiaomi Pad 8

**Test Date:** 2026-11-15  
**Device Under Test:** Xiaomi Pad 8 (`yupei_global` / `25097RP43G`, Serial: `d370854b`)  
**SoC:** Qualcomm Snapdragon 8s Gen 4 (`SM8750-AB`, 1x Cortex-X4 @ 3.01GHz + 4x Cortex-A720 + 3x Cortex-A520)  
**GPU:** Adreno 825 (A8xx)  
**OS:** Android 16 / HyperOS 2.0 (Linux Kernel `6.6.118-android15-8-ge56cf6b09cca-ab15511674-4k aarch64`)  
**Display:** 11.2" 3.2K LCD, 3200 x 2136 @ 144Hz, Landscape Mode 2  
**App Build:** `com.alw.workstation` (Debug Build `94d45f1` + Step 3 / Driver Store Resiliency Patch)

---

## 1. Executive Summary

The **Round 2 On-Device Verification** on the physical **Xiaomi Pad 8** succeeded across all core subsystems. The native PRoot execution engine, Wayland display bridge, low-latency audio bridge, Turnip GPU driver store, and multi-screen Jetpack Compose UI (Dashboard, First-Launch Wizard, Stack Configurator, Shared Driver Store, and PTY Linux Console) were validated on actual hardware under Android 16.

### Key Milestones Validated on Hardware:
1. **Genuine Arch Linux ARM Execution via PRoot:**
   - Base Arch Linux ARM `aarch64` rootfs extracted and verified.
   - `lib_proot.so` executed guest binaries cleanly under Android 16.
   - `pacman --version` executed inside guest environment reporting **Pacman v7.1.0 - libalpm v16.0.1**.
2. **Native 3200x2136 Wayland Surface Attachment:**
   - `libwayland_bridge.so` attached directly to `ANativeWindow` at full panel resolution (`3200x2136`).
   - Surface resize, buffer queue, and compositor presentation pipeline active.
3. **Collapsible Floating Control HUD & Overlay:**
   - Active Wayland surface hosted with floating HUD pill (`ALW · PEN · 0.0ms`).
   - Expanded controls for Creative Pen (`zwp_tablet_tool_v2`), Gaming/Raw relative mouse capture, PulseAudio volume slider, IPC reload, dotfile reset, and session termination.
4. **Shared Turnip KGSL Driver Store:**
   - Driver store indexed `turnip-mesa-25.0.0-adreno825` with `GLIBC_AARCH64` ELF verification.
   - One-tap activation dynamically created `/opt/drivers/turnip/active/` and linked `freedreno_icd.aarch64.json` targeting `/dev/kgsl-3d0`.
5. **Interactive PTY Terminal Console:**
   - Native `forkpty` spawned guest `/bin/bash` with touch accessory toolbar (ESC, TAB, CTRL, ALT, arrows, pipe, slash, tilde).
6. **Phantom Process Killer Mitigation:**
   - HyperOS process limit cap raised from default (32) to `2147483647` via privileged shell (`device_config put activity_manager max_phantom_processes 2147483647`).

---

## 2. Verified Subsystems & Evidence

### A. First-Launch Setup Wizard & OCI Catalog
- **Wizard Step 1:** Distro selection (Arch Linux ARM default, local tarball import, and OCI image catalog). Curated ARM64 chips displayed for `ubuntu:24.04`, `debian:bookworm`, `fedora:40`, `alpine:latest`, `kalilinux/kali-rolling`, and `opensuse/tumbleweed`.
- **Wizard Step 2:** Turnip GPU driver store selection with SHA256 cryptographic verification and glibc ELF validation.
- **Wizard Step 3:** Automated Rootfs Provisioning screen streaming extraction progress and deterministic step indicators.

| Evidence File | Description |
|---|---|
| `round2_screen01_launch.png` | Workstation Dashboard with Xiaomi Pad 8 hardware specs |
| `round2_screen02_wizard_step1.png` | First-Launch Wizard Step 1: Distro & Preset selection |
| `round2_screen03_oci_catalog.png` | OCI Container Catalog browser with ARM64 tags |
| `round2_screen04_wizard_step2.png` | Wizard Step 2: Adreno 825 Turnip KGSL driver selector |
| `round2_screen05_wizard_step3.png` | Wizard Step 3: Automated rootfs provisioning screen |
| `round2_screen06_provisioning_progress.png` | Provisioning progress stream and terminal log view |
| `round2_screen07_wizard_retry.png` | Provisioning error recovery and retry flow |

### B. Shared Turnip Driver Store & GPU Node Binding
- Driver store indexed `turnip-mesa-25.0.0-adreno825` with `GLIBC_AARCH64` ELF ABI.
- Tapping **Activate** cleanly populated `files/drivers/turnip/active/` and generated `freedreno_icd.aarch64.json`.
- Confirmed hardware node `/dev/kgsl-3d0` bound directly without SELinux denial.

| Evidence File | Description |
|---|---|
| `round2_screen08_driver_store.png` | Shared Turnip GPU Driver Store screen |
| `round2_screen09_driver_activated.png` | Activated Turnip KGSL driver bound to active store |

### C. Live Full-Screen Wayland Surface & Floating HUD
- Launched into active desktop session from Dashboard.
- `WaylandSurfaceView` acquired `ANativeWindow` at `3200x2136`.
- Floating HUD pill (`ALW · PEN · 0.0ms`) rendered cleanly in upper-right corner.
- Tapping the pill expanded the full Workstation Controls menu.
- Tapping **Terminate Session** cleanly disconnected the native window and returned to the Dashboard.

| Evidence File | Description |
|---|---|
| `round2_screen10_session_surface.png` | Active Wayland surface with compact floating HUD pill |
| `round2_screen11_hud_expanded.png` | Expanded Workstation Controls overlay |
| `round2_screen12_terminated.png` | Clean session termination and return to Dashboard |

### D. Linux Console (PRoot /bin/bash with PTY) & Stack Configurator
- Native `forkpty` spawned guest interactive shell under `/dev/pts`.
- Touch keyboard toolbar displayed accessory keys.
- StackConfigurator verified for editing custom profiles, compositor switching (Hyprland vs LabWC), and scale factors.

| Evidence File | Description |
|---|---|
| `round2_screen13_terminal.png` | Linux Console (PRoot /bin/bash) with PTY Active |
| `round2_screen14_current.png` | Terminal console view on device |
| `round2_screen15_dashboard.png` | Restored Dashboard workspace manager |
| `round2_screen18_configurator.png` | StackProfile Configurator screen |

---

## 3. Real-Device Runtime Proof (CLI & Logcat Logs)

### 1. Arch Linux ARM Guest Execution Inside PRoot:
```
$ adb -s d370854b shell run-as com.alw.workstation /system/bin/sh -c '
  export PROOT_TMP_DIR=.../files/tmp
  export PROOT_LOADER=.../lib/arm64/lib_loader.so
  export LD_LIBRARY_PATH=.../lib/arm64
  .../lib_proot.so -r .../files/rootfs/archlinux-default -0 -w / /bin/bash -c "
    export PATH=/usr/bin:/bin:/usr/sbin:/sbin
    uname -a
    whoami
    cat /etc/os-release
    pacman --version"
'

Linux localhost 6.6.118-android15-8-ge56cf6b09cca-ab15511674-4k #1 SMP PREEMPT Thu May 28 07:48:46 UTC 2026 aarch64 GNU/Linux
root
NAME="Arch Linux ARM"
PRETTY_NAME="Arch Linux ARM"
ID=archarm
ID_LIKE=arch

 .--.                  Pacman v7.1.0 - libalpm v16.0.1
/ _.-' .-.  .-.  .-.   Copyright (C) 2006-2025 Pacman Development Team
\  '-. '-'  '-'  '-'   Copyright (C) 2002-2006 Judd Vinet
 '--'
```

### 2. Native Wayland Window & AAudio Sink:
```
11-15 15:45:50.811 13751 13751 I ALW::AudioBridgeNative: AAudio stream active: sampleRate=48000, channels=2, low-latency
11-15 15:45:50.811 13751 13751 I ALW::AudioBridgeNative: Audio bridge server listening on .../pulse/native (nullSink=0)
11-15 15:45:50.857 13751 13751 D ViewRootImplStubImpl: onSurfaceViewAttached: vri size:3200x2136
11-15 15:45:50.864 13751 13751 I ALW::WaylandBridgeNative: Native window attached: 0xb400007aa25c5470 (3200x2136)
11-15 15:45:50.864 13751 13751 I ALW::WaylandBridgeNative: Surface resized to 3200x2136
11-15 15:47:00.098 13751 13801 I ALW::PtyBridgeNative: forkpty spawned pid 15024 with masterFd 183
```

---

## 4. Conclusion & Next Phase Readiness

The Xiaomi Pad 8 hardware smoke test confirms that all native engines, PRoot sandboxing, Wayland frame blitter, AAudio low-latency audio sink, Turnip driver store, and Compose UI interfaces function smoothly on the physical Snapdragon 8s Gen 4 / Adreno 825 tablet under Android 16.

All acceptance criteria for Round 2 hardware verification are satisfied.
