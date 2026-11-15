# Verification Evidence: TASK-020 PRoot Rice Compatibility Shim Layer (`/usr/local/bin`)

**Task:** TASK-020  
**Date:** 2026-11-15  
**Target Device:** Xiaomi Pad 8 (`d370854b`, Snapdragon 8s Gen 4, Adreno 825, Android 16 / HyperOS 2.0)  
**Status:** Complete & Verified  

---

## 1. Summary of Changes

1. **`RiceShimGenerator` in `core:proot` (`com.alw.workstation.core.proot.shim.RiceShimGenerator`):**
   - Implemented compatibility shims in `/usr/local/bin` preceding `/usr/bin` in Linux `PATH`:
     - **`systemctl`**: Intercepts `--user` GUI daemons (`waybar`, `swaync`, `mako`, `swww-daemon`, `pipewire`, `wireplumber`, `ags`), appends `exec-once = <unit>` to `~/.config/hypr/autostart.conf`, and spawns daemon in background if running under active Wayland session. Intercepts queries (`is-active`, `is-enabled`, `status`) and system/hardware units, gracefully returning exit code `0`.
     - **`fakeroot`**: Redirects to `fakeroot-tcp` when present to prevent Android SysV IPC queue deadlocks during `makepkg`/`yay` execution.
     - **`makepkg`**: Transparently injects `-A` (`--ignorearch`) for architecture-agnostic AUR themes, icons, and ricing scripts on `aarch64`.
     - **`sudo`**: Strips sudo flags and executes arguments directly under PRoot fake-root mode (`-0`), eliminating PAM authentication failures in unrooted environments.
2. **`ProotRunner` Integration:**
   - Wires `RiceShimGenerator.installShims(guestLocalBin)` into `ProotRunner.buildLaunchCommand`, ensuring shims in guest `/usr/local/bin` are materialized and executable before any guest process or session starts.
3. **Unit Tests:**
   - `RiceShimGeneratorTest` in `core:proot`:
     - `testInstallShimsCreatesAllFourFiles`: PASS
     - `testSystemctlShimContent`: PASS
     - `testFakerootShimContent`: PASS
     - `testMakepkgShimContent`: PASS
     - `testSudoShimContent`: PASS
   - `ProotRunnerTest`:
     - Verified guest `/usr/local/bin` shims are generated and marked executable during command building.

---

## 2. On-Device Verification (Xiaomi Pad 8 `d370854b`)

Execution inside Arch Linux ARM guest container on unrooted Android 16 via PRoot:

```bash
$ which systemctl fakeroot makepkg sudo
/usr/local/bin/systemctl
/usr/local/bin/fakeroot
/usr/local/bin/makepkg
/usr/local/bin/sudo

$ systemctl --user enable waybar
$ systemctl --user enable swaync
$ cat /home/alw/.config/hypr/autostart.conf
exec-once = waybar
exec-once = swaync

$ systemctl --user status waybar && echo WAYBAR_STATUS_OK
WAYBAR_STATUS_OK

$ systemctl status bluetooth && echo BLUETOOTH_STATUS_OK
BLUETOOTH_STATUS_OK

$ systemctl daemon-reload && echo DAEMON_RELOAD_OK
DAEMON_RELOAD_OK

$ sudo id
uid=0(root) gid=0(root) groups=0(root),1004,1007,1011,1015,1028,1078,1079,3001,3002,3003,3006,3009,3011,3012,50417

$ sudo -u root whoami
root

$ sudo -E pacman --version | head -n 2
 .--.                  Pacman v7.1.0 - libalpm v16.0.1
/ _.-' .-.  .-.  .-.   Copyright (C) 2006-2025 Pacman Development Team

$ fakeroot id
uid=0(root) gid=0(root) groups=0(root),1004,1007,1011,1015,1028,1078,1079,3001,3002,3003,3006,3009,3011,3012,50417

$ makepkg --version
makepkg (pacman) 7.1.0
Copyright (c) 2006-2025 Pacman Development Team <pacman-dev@lists.archlinux.org>.
Copyright (C) 2002-2006 Judd Vinet <jvinet@zeroflux.org>.
```

All acceptance criteria for TASK-020 are fully met.
