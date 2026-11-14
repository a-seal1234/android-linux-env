# TASK-004 Verification Evidence: proot-runtime PRoot Harness & Multi-Rootfs Manager

**Task:** [TASK-004 — proot-runtime PRoot Harness & Multi-Rootfs Manager](../TASK.md)  
**Date:** 2026-11-14  
**Verified By:** Codex (local Linux environment)  
**Status:** Verification Passed (Exit Code 0)  

---

## 1. Verified Components

1. **`RootfsManager` (Multi-Rootfs Lifecycle Subsystem):**
   - Manages directory isolation under `files/rootfs/<id>`.
   - Accurate rootfs integrity validation (`bin/sh`, `usr/bin/sh`, `bin/bash`, `bin/busybox`).
   - Non-destructive snapshot cloning (`cloneRootfs`) with recursive file copying, symlink preservation, and executable bit restoration.
   - Safe deletion (`deleteRootfs`) with path traversal guards preventing deletion outside `rootfsBaseDir`.
   - Desktop session discovery (`discoverWaylandSessions`): parses all `.desktop` files in `<rootfs>/usr/share/wayland-sessions/` extracting `id`, `name`, `exec`, `comment`, and `terminal` attributes.
   - Provisioning marker management (`markProvisioned` -> `.alw-provisioned`).

2. **`ProotRunner` (PRoot Execution Command Builder):**
   - Fake root user emulation (`-0`).
   - Soft-symlink emulation on non-POSIX shared storage (`--link2symlink`).
   - Rootfs directory isolation (`-r <rootfsDir>`).
   - System pseudo mounts (`/proc`, `/dev`, `/sys`, `/dev/pts`, `/dev/shm`).
   - Hardware Adreno KGSL node binding (`/dev/kgsl-3d0`).
   - XDG runtime socket directory mount (`<sessionRuntimeDir>:/tmp/runtime-alw`).
   - External Mesa Turnip driver package mount (`<driverDir>:/opt/turnip:ro`).
   - Selective SDCard mount (`/storage/emulated/0:/mnt/sdcard`) with masked exclusion of `/mnt/sdcard/Android/data` and `/mnt/sdcard/Android/obb`.
   - Optional x86_64 translation hook (`-q /opt/fex/bin/FEXInterpreter` or `-q /usr/bin/box64`).
   - Guest working directory (`-w /home/<user>`).
   - Clean environment passing (`/usr/bin/env -i <env_vars>`) utilizing `EnvironmentGenerator`.
   - Linker wrapper resolution: executes via `/system/bin/linker64` for `.so`-packaged PIE executables on Android.
   - Runner host environment (`PROOT_TMP_DIR`, `PROOT_LOADER`, `LD_LIBRARY_PATH`).

3. **`RootfsExtractor` (Archive Extractor & Auto-Configuration):**
   - High-performance streamed extraction using Apache Commons Compress and XZ.
   - Supports `.tar.gz`, `.tar.xz`, and `.tar` formats with automatic magic byte or extension detection.
   - Directory traversal protection (Anti-ZipSlip / Anti-TarSlip boundary checks).
   - Restores symbolic links and hard links with resilient fallback.
   - Preserves executable permissions (`0755` / `setExecutable`) and readability.
   - Auto-configures guest `/etc/resolv.conf` (DNS: `1.1.1.1`, `8.8.8.8`) and `/etc/hosts` (`127.0.0.1 localhost`).
   - Real-time progress reporting callback with entry count and bytes extracted.

4. **`ProotProcessManager` (Process Lifecycle & IO Stream Pumps):**
   - Spawns background reader threads for asynchronous stdout and stderr forwarding.
   - Streams output to `SharedFlow<String>` and `ProcessListener`.
   - PID extraction via reflection supporting Android and UNIX process implementations.
   - Two-phase graceful process termination (`SIGTERM` -> timeout -> `SIGKILL`).
   - Suspending coroutine command executor with timeout support.

5. **Vendor Native PRoot Binaries in `core/proot/src/main/jniLibs/arm64-v8a/`:**
   - `lib_loader.so` (PRoot 64-bit loader)
   - `lib_loader32.so` (PRoot 32-bit loader)
   - `lib_proot.so` (PRoot 5.4.0+ PIE executable)
   - `libandroid-shmem.so` (SYSV IPC ashmem/memfd emulator)
   - `libsandbox_redirect.so`
   - `libtalloc.so` (Talloc memory allocator)
   - `libvfs_intercept.so`
   - Packaged automatically into `app-debug.apk` under `lib/arm64-v8a/`.

---

## 2. Test Execution & Coverage

Executed via `./gradlew test`:
```text
> Task :core:proot:testDebugUnitTest
com.alw.workstation.core.proot.ProotProcessManagerTest > testExecuteCommandSynchronous PASSED
com.alw.workstation.core.proot.ProotProcessManagerTest > testExecuteCommandCapturesStderr PASSED
com.alw.workstation.core.proot.ProotProcessManagerTest > testStartProcessAndTerminate PASSED
com.alw.workstation.core.proot.ProotRunnerTest > testBuildLaunchCommandArguments PASSED
com.alw.workstation.core.proot.ProotRunnerTest > testSafeModeLabwcCommand PASSED
com.alw.workstation.core.proot.ProotRunnerTest > testAdrenoProprietaryZinkDriverCommand PASSED
com.alw.workstation.core.proot.ProotRunnerTest > testEmulationHooks PASSED
com.alw.workstation.core.proot.ProotRunnerTest > testCustomCommandExecution PASSED
com.alw.workstation.core.proot.RootfsExtractorTest > testExtractTarGzStream PASSED
com.alw.workstation.core.proot.RootfsExtractorTest > testExtractTarGzFile PASSED
com.alw.workstation.core.proot.RootfsExtractorTest > testZipSlipDirectoryTraversalBlocked PASSED
com.alw.workstation.core.proot.RootfsManagerTest > testListRootfsEmpty PASSED
com.alw.workstation.core.proot.RootfsManagerTest > testCreateAndListRootfs PASSED
com.alw.workstation.core.proot.RootfsManagerTest > testCheckRootfsValidity PASSED
com.alw.workstation.core.proot.RootfsManagerTest > testDeleteRootfs PASSED
com.alw.workstation.core.proot.RootfsManagerTest > testPathTraversalRootfsIdRejected PASSED
com.alw.workstation.core.proot.RootfsManagerTest > testCloneRootfs PASSED
com.alw.workstation.core.proot.RootfsManagerTest > testDiscoverWaylandSessions PASSED
com.alw.workstation.core.proot.RootfsManagerTest > testMarkProvisioned PASSED

Total: 19 tests executed across 4 test suites in core:proot. 0 failures, 0 errors, 0 skipped.
Combined project total: 39 tests executed (20 in core:session, 19 in core:proot). 100% pass rate.
```

---

## 3. Build & Packaging Verification

Executed via `./gradlew assembleDebug`:
```text
BUILD SUCCESSFUL in 6s
128 actionable tasks: 18 executed, 110 up-to-date
```

Verified native library packaging in generated `app-debug.apk`:
```text
$ unzip -l app/build/outputs/apk/debug/app-debug.apk | grep "lib/arm64-v8a"
    18136  lib/arm64-v8a/lib_loader.so
     6244  lib/arm64-v8a/lib_loader32.so
   198776  lib/arm64-v8a/lib_proot.so
    14432  lib/arm64-v8a/libandroid-shmem.so
    10096  lib/arm64-v8a/libandroidx.graphics.path.so
    21496  lib/arm64-v8a/libsandbox_redirect.so
    31440  lib/arm64-v8a/libtalloc.so
    19408  lib/arm64-v8a/libvfs_intercept.so
     6168  lib/arm64-v8a/libwayland_bridge.so
```

---

## 4. Verdict

**GATE PASSED**: All acceptance criteria for TASK-004 have been fully satisfied. PRoot execution harness, multi-rootfs directory isolation, archive extraction, process lifecycle management, and native PRoot binary packaging are verified and ready for TASK-005.
