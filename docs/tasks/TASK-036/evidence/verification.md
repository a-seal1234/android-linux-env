# TASK-036 Verification Evidence

## Scope & Target
- **Task:** [TASK-036 — Session Lifecycle Guard, Duplicate Detection Prompt, Close All Sessions & App Crash Isolation](../TASK.md)
- **Harness:** Headless Linux development host (local testing)
- **Target Device:** Xiaomi Pad 8 (Snapdragon 8s Gen 4, Adreno 825, HyperOS)
- **Execution Mode:** Unattended overnight headless build & test (device offline; physical ADB operations skipped per constraints)

---

## 1. Implementation Summary

### 1.1 No-Sandbox Isolation Suite
- **`SandboxIsolationContributor.kt`:**
  - Registered in `ProotRunner` launch pipeline with priority 20.
  - Automatically exports:
    - `MOZ_DISABLE_CONTENT_SANDBOX=1`
    - `MOZ_DISABLE_RDD_SANDBOX=1`
    - `MOZ_DISABLE_GMP_SANDBOX=1`
    - `MOZ_DISABLE_SOCKET_PROCESS_SANDBOX=1`
    - `QTWEBENGINE_DISABLE_SANDBOX=1`
    - `ELECTRON_OZONE_PLATFORM_HINT=wayland`
  - Ensures host `dev/shm` directory exists with 1777 permissions and bind-mounts it to guest `/dev/shm`.
- **`/etc/alw/env.d/20-alw-nosandbox.sh` & `/etc/alw/libnosandbox.c`:**
  - Sourced by `alw-gui-run` on every desktop GUI launch.
  - Ensures `/dev/shm` is writable.
  - Automatically launches session D-Bus daemon if `DBUS_SESSION_BUS_ADDRESS` is unset.
  - Injects `libnosandbox.so` via `LD_PRELOAD`, intercepting:
    - `prctl(PR_SET_SECCOMP)` to return success without failing under PRoot translation.
    - `unshare()` flags (`CLONE_NEWUSER`, `CLONE_NEWNS`, `CLONE_NEWPID`, `CLONE_NEWNET`, `CLONE_NEWIPC`, `CLONE_NEWUTS`, `CLONE_NEWCGROUP`) to prevent crashes in unprivileged PRoot environments.
  - Auto-compiles `/etc/alw/libnosandbox.c` with guest `gcc` into `/usr/lib/libnosandbox.so` if source exists and binary is absent.
- **Chromium / Electron `/usr/local/bin` Wrappers:**
  - Generated and installed by `RiceShimGenerator.installShims()` for:
    - `chromium`, `google-chrome`, `google-chrome-stable`, `brave`, `brave-browser`, `electron`, `code`, `code-oss`, `antigravity`.
  - Automatically injects `--no-sandbox` and `--disable-dev-shm-usage` unless already passed by the caller.

### 1.2 Runtime Performance Tuning & CPU Core Pinning
- **`CorePinningContributor.kt`:**
  - Registered in `ProotRunner` launch pipeline with priority 10.
  - Exports `MALLOC_ARENA_MAX=2` into guest environment to mitigate heap fragmentation under PRoot.
  - Unsets `PROOT_NO_SECCOMP` in host runner environment to enable seccomp filtering acceleration.
  - Hooks `onProcessSpawned(pid)` to pin root guest PID and process tree to performance cores (CPUs 3–7, mask `0xf8`) using `taskset -p 0xf8 <pid>`.
- **`/etc/alw/env.d/30-alw-runtime.sh`:**
  - Export `MALLOC_ARENA_MAX=2`.
  - Executes `taskset -p 0xf8 $$` on guest shell startup.

### 1.3 Close All Sessions Action
- **`SessionCleanupCoordinator.kt`:**
  - Two-stage SIGTERM -> grace timeout (500ms) -> SIGKILL process cleanup for same-UID guest processes (`lib_proot.so`, `Hyprland`, `quickshell`, `waybar`, etc.), sparing terminal sessions.
  - Purges stale runtime artifacts (`wayland-*`, `wayland-*.lock`, `alw-host.sock`, `.hyprland.lock`).
  - Resets Wayland server bridge and resets `SessionController` to `SessionState.Idle`.
- **UI Integrations:**
  - Dashboard: "Close All Sessions" action button and recovery prompt dialog.
  - In-session HUD / `FloatingControlPill`: "Close All Sessions" quick action.

---

## 2. Test Verification

### Headless Gradle Verification
Executed:
```bash
./gradlew test assembleDebug
```
Result: **BUILD SUCCESSFUL**
- All unit test suites passed, including:
  - `ProotLaunchContributorTest`: verified `CorePinningContributor`, `SandboxIsolationContributor`, priority ordering, `/dev/shm` bind mount, and default contributor application.
  - `RiceShimGeneratorTest`: verified `20-alw-nosandbox.sh`, `30-alw-runtime.sh`, `libnosandbox.c`, and Chromium/Electron binary wrappers in `/usr/local/bin`.
  - `SessionCleanupCoordinatorTest`: verified two-stage SIGTERM -> SIGKILL escalation, stale artifact detection, and artifact purge.
  - `DashboardViewModelTest`: verified `closeAllSessions()` UI actions.
- `assembleDebug` completed successfully, producing valid APK and native libraries (`wayland_bridge.so`).

---

## 3. Physical Device Status
- **Target:** Xiaomi Pad 8 (`192.168.51.161:44021`)
- **Status:** Offline overnight during unattended run.
- **Classification:** `Code Complete — On-Device Verification Pending`
