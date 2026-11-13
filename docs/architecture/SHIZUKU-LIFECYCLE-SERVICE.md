# Shizuku Companion & Lifecycle Service Specification

**Status:** Approved Specification (Phase 0)  
**Document:** `docs/architecture/SHIZUKU-LIFECYCLE-SERVICE.md`  
**Android Compatibility:** Android 10 (API 29) to Android 15 (API 35)  
**Special Target:** Xiaomi HyperOS 2.0 (Snapdragon 8s Gen 4)  

---

## 1. Overview & Problem Statement

Running complex Linux workloads (such as compiling large Rust crates, running language servers, and executing Electron applications) on Android encounters three aggressive OS-level constraints:
1. **CPU Sleep (Doze Mode):** When the tablet screen turns off, the Linux session freezes unless a `PARTIAL_WAKE_LOCK` is held.
2. **Android 12+ Phantom Process Killer:** Android tracks processes spawned by an app's child processes. If the total number of phantom processes exceeds 32 (default), Android kills them with `SIGKILL`, disrupting compilation jobs.
3. **Xiaomi HyperOS PowerKeeper:** Xiaomi's proprietary battery manager aggressively terminates long-running background processes regardless of standard Android foreground services unless explicitly whitelisted.

ALW solves these issues through a dual-tier lifecycle architecture:
- **Tier 1 (Standard Android Sandbox):** Foreground Service with sticky notification and `PARTIAL_WAKE_LOCK`.
- **Tier 2 (Shizuku Companion `UID 2000`):** Elevated ADB-level control to disable phantom process limits and whitelist PowerKeeper.

```
+--------------------------------------------------------------------------+
|                  Android Workstation Lifecycle Manager                   |
|                                                                          |
|   +-----------------------------+     +------------------------------+   |
|   |   Tier 1: Standard Sandbox  |     |   Tier 2: Shizuku Companion  |   |
|   | - Foreground Service        |     | - max_phantom_processes      |   |
|   | - PARTIAL_WAKE_LOCK         |     | - HyperOS PowerKeeper bypass |   |
|   | - Battery Optimization req  |     | - Adreno KGSL Telemetry      |   |
|   +--------------+--------------+     +--------------+---------------+   |
+------------------|-----------------------------------|-------------------+
                   v                                   v
+--------------------------------------------------------------------------+
|                        Android OS / Linux Kernel                         |
|   Continuous Compilation, 32+ Threads, 144Hz Uninterrupted Session       |
+--------------------------------------------------------------------------+
```

---

## 2. Tier 1: Foreground Service & WakeLock

1. **Foreground Service:**
   - Manifest declares `android:foregroundServiceType="specialUse|mediaPlayback"`.
   - Displays an ongoing, non-dismissible notification with live session metrics (CPU load, active rootfs, uptime).
2. **WakeLock Management:**
   - On session launch, acquires `PowerManager.PARTIAL_WAKE_LOCK` with a descriptive tag (`ALW::SessionWakeLock`).
   - Released strictly when the user stops the session or exits the desktop.

---

## 3. Tier 2: Shizuku Companion (`UID 2000`)

[Shizuku](https://shizuku.rikka.app/) provides unrooted Android applications with access to system APIs running with `UID 2000` (ADB shell privileges) via inter-process communication.

### 3.1 Disabling the Phantom Process Killer
On initial connect, ALW issues the following ADB-level command via Shizuku:
```bash
/system/bin/device_config put activity_manager max_phantom_processes 2147483647
```
This increases the phantom process limit from 32 to 2.14 billion, allowing tools like `cargo build -j8` or `make -j8` to spawn compilation sub-processes without risk of termination.

### 3.2 Bypassing Xiaomi HyperOS PowerKeeper
Xiaomi devices enforce strict background killing via the `com.miui.powerkeeper` package. ALW utilizes Shizuku to disable battery restrictions:
```bash
# Whitelist ALW package from powerkeeper battery restrictions
dumpsys deviceidle whitelist +<package-id>
cmd appops set <package-id> RUN_IN_BACKGROUND allow
cmd appops set <package-id> RUN_ANY_IN_BACKGROUND allow
```

---

## 4. Adreno KGSL GPU Telemetry Streaming

When Shizuku is active (or on devices where `/sys/class/kgsl/` is readable by the app user), the workstation streams hardware telemetry into the Compose HUD:

### Monitored Hardware Metrics
1. **GPU Clock Frequency:**
   `/sys/class/kgsl/kgsl-3d0/gpuclk` (e.g. 295 MHz idle, up to 900+ MHz peak on Adreno 825)
2. **GPU Busy Percentage:**
   `/sys/class/kgsl/kgsl-3d0/gpubusy`
3. **GPU Temperature:**
   `/sys/class/thermal/thermal_zone*/temp` (filtered for GPU thermal zones)
4. **KGSL Memory Allocation:**
   Parsed from `/d/kgsl/proc/<pid>/mem` or kernel ioctl accounting.

---

## 5. Graceful Degradation Strategy

When Shizuku is unavailable or uninstalled:
1. ALW operates in **Tier 1 Mode** (Foreground Service + WakeLock).
2. The UI displays an advisory card informing the user:
   > *"Shizuku is not running. Compilation jobs with >32 concurrent child processes may be stopped by Android. Tap to view manual Wireless Debugging setup instructions."*
3. The core desktop session, Wayland surface, and Turnip GPU acceleration continue to operate normally.
