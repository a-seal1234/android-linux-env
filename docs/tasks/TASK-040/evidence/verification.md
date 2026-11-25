# TASK-040 Verification Evidence

## Scope & Target
- **Task:** [TASK-040 — Per-App PRoot Isolation Engine & Crash Cascade Shield](../TASK.md)
- **Harness:** Headless Linux development host (local testing)
- **Target Device:** Xiaomi Pad 8 (Snapdragon 8s Gen 4, Adreno 825, HyperOS)
- **Execution Mode:** Headless build & unit test verification (`./gradlew test assembleDebug`)

---

## 1. Implementation Summary

### 1.1 Host IPC & Isolated Launcher Engine
- **`RiceHostBridge.kt` (`core:session`):**
  - Added `RiceHostBridge.AppSpawnHandler` functional interface for spawning isolated guest applications.
  - Implemented `app_spawn` and `app-spawn` request verbs over `$XDG_RUNTIME_DIR/alw-host.sock`.
  - Added unit test suite in `RiceHostBridgeAppSpawnTest.kt` verifying command routing, argument parsing, error propagation, and PID return format (`ok=1 pid=<pid>`).

- **`ProotAppLauncher.kt` (`core:proot`):**
  - Implements `RiceHostBridge.AppSpawnHandler`.
  - Uses `ProotRunner.buildLaunchCommand(profile, sessionRuntimeDir, customExecCommand, customArgs)` to synthesize isolated PRoot launch commands with exact rootfs bind mounts, `/tmp/rt` runtime dir, Turnip GPU node, and environment configuration.
  - Injects `ALW_ISOLATED=1` into the runner environment to prevent recursive delegation.
  - Maintains a thread-safe registry of active managed processes (`activeProcesses`) with clean lifecycle termination via `terminateAll()`.
  - Added unit test suite in `ProotAppLauncherTest.kt` covering isolated argument synthesis, environment injection, missing profile error handling, and process termination.

- **`DashboardViewModel.kt` & `MainActivity.kt` (`app`):**
  - Initialized `ProotAppLauncher` using active session profile and fakefs runtime dir.
  - Passed `appSpawnHandler` into `DashboardViewModel` and attached to `RiceHostBridge`.
  - Linked `closeAllSessions()` and `stopSession()` to terminate isolated apps alongside the main compositor.

### 1.2 Guest Shims & Wrappers
- **`RiceShimGenerator.kt` (`core:proot`):**
  - Added `/usr/local/bin/alw-app-exec` shim: connects to `$XDG_RUNTIME_DIR/alw-host.sock` and delegates application spawning to Android host, falling back to `alw-gui-run` if the socket is absent.
  - Updated `generateChromiumWrapper` (covering `code`, `code-oss`, `chromium`, `electron`, `antigravity`): automatically forwards to `alw-app-exec` when `ALW_ISOLATED` is unset.
  - Updated `FIREFOX_SHIM` (covering `alw-firefox` and `firefox`): automatically forwards to `alw-app-exec` when `ALW_ISOLATED` is unset.
  - Materialized `/usr/local/bin/firefox` and `/usr/local/bin/alw-app-exec` in `installShims`.
  - Added unit tests in `RiceShimGeneratorTest.kt` verifying executable file creation and shim content.

---

## 2. Test Verification

```
./gradlew test
BUILD SUCCESSFUL in 9s
159 actionable tasks: 23 executed, 136 up-to-date

./gradlew :app:assembleDebug
BUILD SUCCESSFUL in 3s
96 actionable tasks: 8 executed, 88 up-to-date
```
- All unit tests across all 4 modules (`:app`, `:bridge:wayland`, `:core:proot`, `:core:session`) pass with 0 failures.
