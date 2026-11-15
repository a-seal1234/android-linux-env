# TASK-019 — Centralized Virtual Fake Filesystem (`FakeFsManager`)

**Epic:** [EPIC-ALW-P1 — Phase 1](../../initiatives/android-linux-workstation/epics/phase-1-core-runtime-proot/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Implementation / Core Engine Task  
**Board:** [Central task board](../../../TASKS.md) is the only workflow-status source.  
**Owner / harness:** Antigravity / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Risk:** High Risk  
**Source:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md)  

---

## 1. Outcome

Implement `FakeFsManager` in `core:proot` to provide a robust host-backed virtual filesystem layer for unrooted PRoot execution on Android 16 (HyperOS 2.0). Materialize writable host-backed runtime directories (`/dev/shm`, `/tmp`, `/var/tmp`, `/run`, `/run/lock`, `/run/dbus`, `/run/user/1000`), static network resolution files (`/etc/resolv.conf`, `/etc/hosts`), and synthetic `/proc` nodes (`/proc/stat`, `/proc/uptime`, `/proc/loadavg`, `/proc/version`) that are selectively bound into PRoot when Android 16 SELinux denies unrooted read access to host `/proc/*`. Wire bindings into `ProotRunner` while keeping host `/dev/pts` unmounted to preserve guest PRoot PTY allocation.

---

## 2. Source Requirements

- **REQ-PROOT-001**: Unrooted execution without root or host modification.
- **Android 16 SELinux Compatibility**: Android 16 enforces strict SELinux policies blocking unrooted access to `/proc/stat`, `/proc/uptime`, `/proc/loadavg`, and `/proc/version`, which causes tools like `fastfetch`, `htop`, `procps`, and build scripts to crash or fail.
- **POSIX Filesystem Semantics**: Tools, Wayland compositors, and D-Bus daemons require writable `/tmp`, `/dev/shm`, `/run`, `/run/user/1000`, and `/var/tmp` without encountering guest rootfs filesystem corruption or permission issues.
- **PTY Intactness**: Host `/dev/pts` must remain unmounted in PRoot so PRoot's internal PTY interception (`openpty`/`forkpty`) handles pseudo-terminals cleanly.

---

## 3. Planned Scope & Acceptance Criteria

1. **`FakeFsManager` implementation (`core:proot`):**
   - Base fakefs root: `files/fakefs/` under app files dir.
   - Materialize directories:
     - `dev/shm` (permissions 0777)
     - `tmp` (permissions 0777)
     - `var/tmp` (permissions 0777)
     - `run` (permissions 0755)
     - `run/lock` (permissions 0777)
     - `run/dbus` (permissions 0755)
     - `run/user/1000` (permissions 0700)
   - Materialize network configuration files:
     - `etc/resolv.conf` with standard nameservers (`nameserver 1.1.1.1\nnameserver 8.8.8.8\n`).
     - `etc/hosts` with loopback mappings (`127.0.0.1 localhost\n::1 localhost\n`).
   - Materialize synthetic `/proc` nodes:
     - `proc/stat`: Multi-core cpu stats matching hardware topology (e.g. 8 cores for Snapdragon 8s Gen 4: 1 Prime Cortex-X4 + 4 Performance Cortex-A720 + 3 Efficiency Cortex-A520).
     - `proc/uptime`: Valid floating point uptime and idle time.
     - `proc/loadavg`: Valid load averages and process counts (`0.10 0.08 0.05 1/250 1000`).
     - `proc/version`: Linux kernel 6.6.0-alw-proot-aarch64 GNU/Linux string.
   - Detect host readability of `/proc/*`: If host `/proc/stat` (etc.) cannot be read due to SELinux `EACCES`, return bind mount arguments for the synthetic nodes. If readable, omit the override.
2. **`ProotRunner` wiring:**
   - Incorporate `FakeFsManager.getBinds()` into `ProotRunner.buildLaunchCommand`.
   - Ensure host `/dev/pts` is NEVER mounted as `-b /dev/pts`.
   - Maintain guest `/dev/shm`, `/tmp`, `/run`, `/etc/resolv.conf`, `/etc/hosts` bindings.
3. **Unit Tests & Verification:**
   - Unit tests for `FakeFsManagerTest` in `core:proot` verifying directory creation, file content generation, and selective `/proc` binding logic.
   - Unit test updates for `ProotRunnerTest`.
   - Live hardware verification on Xiaomi Pad 8 (`d370854b`): verify `cat /proc/stat`, `cat /proc/uptime`, `cat /proc/version`, and `cat /proc/loadavg` succeed inside PRoot.

---

## 4. Dependencies & Gates

- **Prerequisites:** TASK-004, TASK-017
- **Implementation Gate:** Completed.
- **Verification Gate:** All unit tests pass, assembleDebug passes, verified on connected physical Xiaomi Pad 8 (`d370854b`).
- **Review Gate:** Complete task handoff and evidence documentation in [verification.md](evidence/verification.md). Status: **Done**.
