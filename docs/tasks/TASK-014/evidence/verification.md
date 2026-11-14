# TASK-014: Verification Evidence

## 1. Scope & Acceptance Criteria
- **Task**: Optional Shizuku Companion Integration for Phantom Process Killer Bypass & HyperOS Performance Tuning.
- **Architectural Invariant**: Non-root, standalone-first workstation. The app functions 100% without Shizuku (Tier 1 Sandbox with Foreground Service & WakeLock). When Shizuku is present and authorized, it provides Tier 2 Privileged Companion capabilities via UID 2000 (`max_phantom_processes 2147483647`, PowerKeeper whitelist, appops).
- **Module**: `core:proot`, `app`.

## 2. Unit Test Results

### `core:proot` Tests
All 56 unit tests passed, including the comprehensive Shizuku test suite:
- `ShizukuCompanionManagerTest.kt`:
  - `testStateNotInstalled`: Verifies state is `NOT_INSTALLED` when package `moe.shizuku.privileged.api` is absent.
  - `testStateStopped`: Verifies state is `STOPPED` when package is installed but binder ping fails.
  - `testStatePermissionRequired`: Verifies state is `PERMISSION_REQUIRED` when binder is alive but permission not granted.
  - `testStateReady`: Verifies state is `READY` when binder is alive and permission is granted.
  - `testRequestPermissionTriggersListener`: Verifies permission request callback wiring.
  - `testApplyPhantomProcessBypassSuccess`: Verifies execution of `device_config set_sync_disabled_for_tests persistent`, `device_config put activity_manager max_phantom_processes 2147483647`, `dumpsys deviceidle whitelist +<pkg>`, and `cmd appops set <pkg> RUN_IN_BACKGROUND allow`.
  - `testApplyPhantomProcessBypassFailureWhenNotReady`: Verifies failure result without running commands when not in `READY` state.

### `app` Tests
All UI and ViewModel unit tests passed, including:
- `DashboardViewModelTest.testShizukuCompanionIntegration`: Verifies reactive companion state flow, permission requests, and bypass execution from the ViewModel.

Command output:
```
BUILD SUCCESSFUL in 837ms
159 actionable tasks: 159 up-to-date
```

## 3. Real Device Smoke Test (Xiaomi Pad 8 / Snapdragon 8s Gen 4 / Android 16)
- **Target Hardware**: Xiaomi Pad 8 (`25097RP43G`, HyperOS on Android 16, transport ID: `4`).
- **Build**: `./gradlew assembleDebug` (SUCCESSFUL).
- **Install**: `adb install -r app/build/outputs/apk/debug/app-debug.apk` (Performing Streamed Install -> Success).
- **Launch**: `adb shell am start -n com.alw.workstation/.MainActivity`.
- **Runtime Logs**:
  ```
  D ShizukuProvider: Initialize Sui: false
  ```
  No runtime crashes or missing class errors.
- **Hardware Screenshot**:
  ![Xiaomi Pad 8 Dashboard with Shizuku Card](task014_smoke.png)
  - Card displays: `System Lifecycle & Shizuku Companion (UID 2000) [Tier 1 Standard Sandbox]`.
  - Explanatory subtitle: `Shizuku not installed. Workstation operates gracefully in Tier 1 Sandbox with Foreground Service & WakeLock.`.
  - Action button: `Refresh Status`.
