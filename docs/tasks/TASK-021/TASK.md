# TASK-021 — Full `Hyprland` + `kitty` + Turnip KGSL Provisioning & Verification

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Integration / Provisioning & On-Device Validation Task  
**Board:** [Central task board](../../../TASKS.md) is the only workflow-status source.  
**Owner / harness:** Antigravity / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Risk:** Critical  
**Source:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md)  

---

## 1. Outcome

Fully provision the Arch Linux ARM rootfs on the physical Xiaomi Pad 8 (`d370854b`) with modern Wayland desktop packages (`hyprland`, `kitty`, `mesa`, `vulkan-tools`, `fastfetch`, `fakeroot-tcp`). Configure `pacman.conf` with `DisableSandbox` for Pacman 7 unrooted PRoot operation. Verify hardware Vulkan acceleration on the Adreno 825 via Turnip KGSL (`/dev/kgsl-3d0`) with `vulkaninfo --summary`. Validate system metrics parsing via synthetic `/proc` nodes in `fastfetch`. Boot the complete graphical **Creative Workstation (`Hyprland`)** session at 3200x2136 resolution with `kitty` running interactively on the physical display, capturing screenshot evidence.

---

## 2. Source Requirements

- **REQ-PACMAN-001**: Pacman 7 sandbox isolation fails inside unrooted PRoot without `DisableSandbox` in `/etc/pacman.conf`.
- **REQ-GPU-001**: Direct hardware Vulkan acceleration via Turnip Adreno 825 KGSL driver bound to `/dev/kgsl-3d0`.
- **REQ-COMPOSITOR-001**: Hyprland nested compositor startup on Wayland surface bridge at native 3200x2136 resolution.
- **REQ-TERMINAL-001**: High-performance GPU-accelerated terminal emulator (`kitty`) functional under Hyprland.

---

## 3. Planned Scope & Acceptance Criteria

1. **`ArchProvisioningScriptBuilder` Hardening:**
   - Automatically inspect `/etc/pacman.conf` and uncomment or inject `DisableSandbox` under `[options]`.
   - Maintain fast reliable mirror selection (Tsinghua / USTC / Arch Linux ARM).
   - Verify unit tests in `core:proot` pass cleanly.
2. **On-Device Package Provisioning (`d370854b`):**
   - Synchronize pacman databases (`pacman -Sy`).
   - Install required packages: `hyprland`, `kitty`, `mesa`, `vulkan-tools`, `fastfetch`, `fakeroot-tcp`.
   - Verify `fakeroot-tcp` operates without SysV IPC hanging.
3. **Turnip KGSL Hardware Acceleration Validation:**
   - Execute `vulkaninfo --summary` inside PRoot with Turnip ICD active.
   - Verify device name reports `Turnip Adreno (TM) 825` (or Adreno 8xx Vulkan driver) and API version >= 1.3.
4. **Synthetic `/proc` Metrics Validation:**
   - Execute `fastfetch` inside PRoot.
   - Confirm OS, kernel (`6.6.0-alw-proot-aarch64`), CPU (`Snapdragon 8s Gen 4` / 8 cores), memory, and uptime render without crashes.
5. **Interactive Hyprland + Kitty Desktop Session:**
   - Launch session using `WorkstationService` / `SessionController` or PRoot session launcher targeting Hyprland at 3200x2136.
   - Auto-launch `kitty` terminal in Hyprland.
   - Capture live screenshot evidence on device (`screen_task021_hyprland_kitty.png`).

---

## 4. Dependencies & Gates

- **Prerequisites:** TASK-019, TASK-020, TASK-009, TASK-010, TASK-017
- **Implementation Gate:** Completed.
- **Verification Gate:** Packages installed on device, `vulkaninfo --summary` Turnip output verified, `fastfetch` output verified, Hyprland + kitty rendered at 3200x2136 on Xiaomi Pad 8 screen.
- **Review Gate:** Complete task handoff and evidence documentation in [verification.md](evidence/verification.md). Status: **Planned**.
