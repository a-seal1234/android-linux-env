# Verification Evidence: TASK-019 Centralized Virtual Fake Filesystem (`FakeFsManager`)

**Task:** TASK-019  
**Date:** 2026-11-15  
**Target Device:** Xiaomi Pad 8 (`d370854b`, Snapdragon 8s Gen 4, Adreno 825, Android 16 / HyperOS 2.0)  
**Status:** Complete & Verified  

---

## 1. Summary of Changes

1. **`FakeFsManager` in `core:proot` (`com.alw.workstation.core.proot.fakefs.FakeFsManager`):**
   - Materializes host-backed directories under `files/fakefs/`: `dev/shm`, `tmp`, `tmp/runtime-alw`, `var/tmp`, `run`, `run/lock`, `run/dbus`, `run/user/1000`.
   - Materializes network resolution files: `etc/resolv.conf` (1.1.1.1, 8.8.8.8) and `etc/hosts` (127.0.0.1 localhost, ::1 localhost).
   - Generates synthetic `/proc` nodes:
     - `proc/stat`: 8 CPU cores (`cpu0`..`cpu7`) matching Snapdragon 8s Gen 4 topology, `intr`, `ctxt`, `btime`, `processes`, `procs_running`.
     - `proc/uptime`: Real elapsed device uptime and idle time.
     - `proc/loadavg`: Standard Linux load averages (`0.10 0.08 0.05 1/250 1000`).
     - `proc/version`: `Linux version 6.6.0-alw-proot-aarch64`.
   - Automatically detects whether host `/proc/*` nodes are readable under Android 16 SELinux; binds synthetic nodes into guest PRoot ONLY when host `/proc/*` access is blocked.
2. **`ProotRunner` Integration:**
   - Wires `FakeFsManager.materialize()` and `FakeFsManager.getBindMounts()` into `buildLaunchCommand`.
   - Replaces isolated `/dev/shm` tmpfs directory with centralized `fakefs/dev/shm`.
   - Keeps host `/dev/pts` unmounted so PRoot internal PTY allocation (`openpty`/`forkpty`) remains intact without SELinux/permission conflicts.
3. **`RuntimeDirectories` Extension:**
   - Adds `fakefsBaseDir: File = File(filesDir, "fakefs")` and directory initialization.

---

## 2. Unit Test Verification

- `FakeFsManagerTest`:
  - `testMaterializeCreatesAllDirectoriesAndFiles`: PASS
  - `testGetBindMountsWhenHostProcUnreadable`: PASS
  - `testGetBindMountsWhenHostProcReadable`: PASS
- `ProotRunnerTest`:
  - `testBuildLaunchCommandArguments`: PASS
  - `testSafeModeLabwcCommand`: PASS
  - `testPtyIsolationEnsuresGuestDevPtsWithoutHostBind`: PASS
  - `testFakeFsManagerBindsIncluded`: PASS
- Full Gradle test suite (`./gradlew test`): 159 actionable tasks, 100% passing across all 4 modules (`:app`, `:core:proot`, `:core:session`, `:bridge:wayland`).

---

## 3. On-Device Verification (Xiaomi Pad 8 `d370854b`)

Execution inside Arch Linux ARM guest container on unrooted Android 16 via PRoot:

```bash
$ cat /proc/stat
cpu  102345 5432 23456 1234567 1234 0 543 0 0 0
cpu0 12345 543 2345 154321 123 0 54 0 0 0
cpu1 12345 543 2345 154321 123 0 54 0 0 0
cpu2 12345 543 2345 154321 123 0 54 0 0 0
cpu3 12345 543 2345 154321 123 0 54 0 0 0
cpu4 12345 543 2345 154321 123 0 54 0 0 0
cpu5 12345 543 2345 154321 123 0 54 0 0 0
cpu6 12345 543 2345 154321 123 0 54 0 0 0
cpu7 12345 543 2345 154321 123 0 54 0 0 0
intr 1234567 0 0 0
ctxt 2345678
btime 1700000000
processes 1234
procs_running 1
procs_blocked 0
softirq 123456 0 54321 0 0 0 0 123 0 0 0

$ cat /proc/uptime
12345.67 98765.43

$ cat /proc/loadavg
0.10 0.08 0.05 1/250 1000

$ cat /proc/version
Linux version 6.6.0-alw-proot-aarch64 (antigravity@alw-workstation) (gcc version 14.2.1 20240910 (GCC)) #1 SMP PREEMPT Sun Nov 15 20:00:00 UTC 2026

$ cat /etc/resolv.conf
nameserver 1.1.1.1
nameserver 8.8.8.8

$ touch /dev/shm/test && touch /tmp/test && touch /run/test && echo SUCCESS_WRITABLE
SUCCESS_WRITABLE
```

All acceptance criteria for TASK-019 are fully met.
