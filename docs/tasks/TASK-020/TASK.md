# TASK-020 — PRoot Rice Compatibility Shim Layer (`/usr/local/bin`)

**Epic:** [EPIC-ALW-P1 — Phase 1](../../initiatives/android-linux-workstation/epics/phase-1-core-runtime-proot/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Implementation / Usability & Compatibility Task  
**Board:** [Central task board](../../../TASKS.md) is the only workflow-status source.  
**Owner / harness:** Antigravity / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Risk:** High Risk  
**Source:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md)  

---

## 1. Outcome

Implement an automated PRoot compatibility shim layer in `/usr/local/bin` (which precedes `/usr/bin` in Linux `PATH`) to enable popular Arch Linux / Hyprland rice installation scripts (e.g. `end-4/dots-hyprland`, `sekaipop`, Omura, ML4W) to execute successfully without failing on unsupported systemd PID 1 calls, PAM authentication blocks, SysV IPC message queue restrictions, or architecture checks.

---

## 2. Source Requirements

- **Systemd Emulation in PRoot**: Third-party rice scripts invoke `systemctl --user enable/start <daemon>` for GUI components (`waybar`, `swaync`, `mako`, `swww-daemon`, `pipewire`, `wireplumber`, `ags`). Because PRoot lacks systemd PID 1, direct calls fail.
- **SysV IPC Limitations**: Android kernel blocks SysV IPC (`msgrcv`, `semget`, `shmget`), causing standard `fakeroot` to hang indefinitely during `makepkg` or `yay` AUR package building.
- **AUR Architecture Restrictions**: Many AUR packages specify `arch=('x86_64')` despite consisting of pure shell scripts, themes, or font assets that work cleanly on `aarch64`.
- **Sudo / PAM Bypass**: PRoot runs in fake-root mode (`-0`). Standard `sudo` often fails due to missing setuid or PAM policy restrictions.

---

## 3. Planned Scope & Acceptance Criteria

1. **Shim Generator (`RiceShimGenerator` in `core:proot`):**
   - Materialize shims in `<fakefs>/usr/local/bin/` (or directly within rootfs `/usr/local/bin/` with permissions `0755`):
   - **`systemctl` wrapper**:
     - Handle `systemctl --user start/enable/restart/status <unit>`.
     - Detect GUI daemons: `waybar`, `swaync`, `mako`, `swww-daemon`, `pipewire`, `wireplumber`, `ags`.
     - Automatically append `exec-once = <unit>` to `~/.config/hypr/autostart.conf` if not already present.
     - Launch daemon in the background (`nohup <unit> &>/dev/null &`) if running inside an active Wayland session.
     - For other system units (hardware, system services, journald), gracefully return exit code `0`.
   - **`fakeroot` wrapper**:
     - Automatically check for presence of `fakeroot-tcp`.
     - If `fakeroot-tcp` exists, exec `fakeroot-tcp "$@"` to avoid Android SysV IPC hangs.
     - Otherwise forward to `/usr/bin/fakeroot`.
   - **`makepkg` wrapper**:
     - Transparently inject `-A` (`--ignorearch`) into invocations unless already specified, preventing architecture mismatch aborts for AUR rices on `aarch64`.
     - Ensure fallback to `/usr/bin/makepkg`.
   - **`sudo` wrapper**:
     - If current user is already root (UID 0 in PRoot), directly execute args: `exec "$@"`.
     - If non-root, invoke `/usr/bin/sudo -E "$@"` or direct execution without PAM prompts.
2. **Integration with `ProotRunner` / `ArchProvisioner`:**
   - Integrate shim generation during rootfs provisioning and runtime initialization.
   - Ensure `PATH` in guest environment begins with `/usr/local/bin:/usr/bin:/bin:/usr/local/sbin:/usr/sbin:/sbin`.
3. **Unit Tests & Verification:**
   - Unit tests in `core:proot` validating script generation, argument parsing, autostart injection, and fallback paths.
   - Live hardware verification on Xiaomi Pad 8 (`d370854b`): verify `systemctl --user enable waybar`, `fakeroot`, `makepkg`, and `sudo` behave as expected.

---

## 4. Dependencies & Gates

- **Prerequisites:** TASK-019, TASK-005
- **Implementation Gate:** Completed.
- **Verification Gate:** All unit tests pass, assembleDebug passes, verified on connected physical Xiaomi Pad 8 (`d370854b`).
- **Review Gate:** Complete task handoff and evidence documentation in [verification.md](evidence/verification.md). Status: **Done**.
