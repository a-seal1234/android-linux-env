# TASK-004 — proot-runtime PRoot Harness & Multi-Rootfs Manager

**Epic:** [EPIC-ALW-P1 — Phase 1](../../initiatives/android-linux-workstation/epics/phase-1-core-runtime-proot/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Implementation / Core Runtime & Container Harness  
**Board:** [Central task board](../../../TASKS.md) is the only workflow-status source.  
**Owner / harness:** Codex / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Risk:** High Risk (Ptrace syscall emulation, process execution, multi-rootfs directory isolation)  
**Source:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md) (REQ-PROOT-001, REQ-PROOT-002, REQ-PROOT-003, REQ-PROOT-004, REQ-PROOT-005)

---

## 1. Outcome

Implement the complete unrooted Linux execution and multi-rootfs subsystem in `core:proot`:
1. **Multi-Rootfs Lifecycle Manager (`RootfsManager`):** Directory-based filesystem isolation under `files/rootfs/<id>/`, directory listing, rootfs integrity validation (`bin/sh` or `usr/bin/sh`), snapshot cloning (`cloneRootfs`), safe deletion (`deleteRootfs`), and desktop session discovery (`/usr/share/wayland-sessions/*.desktop`).
2. **PRoot Execution Harness (`ProotRunner`):** Command builder assembling PRoot flags:
   - Rootfs directory isolation (`-r <rootfs_path>`).
   - Fake root user emulation (`-0`).
   - Non-POSIX symlink emulation (`--link2symlink` / `--symlink2file`).
   - System pseudo filesystem mounts (`/proc`, `/dev`, `/sys`, `/dev/pts`, `/dev/shm`).
   - Hardware Adreno KGSL node binding (`/dev/kgsl-3d0`).
   - Wayland and PipeWire socket directory binding (`${sessionRuntimeDir}:/tmp/runtime-alw`).
   - External Mesa Turnip driver mount (`${driverDir}:/opt/turnip:ro`).
   - Selective `/storage/emulated/0` binding to `/mnt/sdcard` (excluding `Android/data`).
   - Foreign binary translation hook (`-q /opt/fex/bin/FEXInterpreter`).
   - High-speed UFS build cache environment redirection (`CARGO_TARGET_DIR`, `UV_PROJECT_ENVIRONMENT`, `PNPM_STORE_DIR`, `GOCACHE`).
   - Android dynamic linker execution (`/system/bin/linker64`) or direct binary invocation.
3. **Archive Extractor & Rootfs Provisioner (`RootfsExtractor`):** Streamed archive unpacker supporting `.tar.gz`, `.tar.xz`, and `.tar` formats with POSIX/GNU tar header handling, permission preservation (`setExecutable`), symlink creation, progress reporting, and `/etc/resolv.conf` auto-configuration.
4. **Process Execution & Lifecycle Engine (`ProotProcessManager`):** Manages spawned Linux processes, asynchronous stdout/stderr stream forwarding, execution cancellation, exit code monitoring, and non-blocking coroutine execution.
5. **Vendor Native PRoot Binaries:** Integrated `lib_proot.so`, `lib_loader.so`, `lib_loader32.so`, `libtalloc.so`, `libandroid-shmem.so`, `libsandbox_redirect.so`, `libvfs_intercept.so` in `core:proot/src/main/jniLibs/arm64-v8a/`.

---

## 2. Planned Scope & Acceptance Criteria

- **AC-1 (Multi-Rootfs Management):** `RootfsManager` lists installed rootfs instances, accurately detects valid vs incomplete rootfs filesystems, safely clones existing rootfs trees via recursive directory copying, and safely deletes rootfs instances.
- **AC-2 (Wayland Session Discovery):** `RootfsManager.discoverWaylandSessions(rootfsId)` parses all `.desktop` files in `<rootfs>/usr/share/wayland-sessions/` extracting `id`, `name`, `exec`, and comments.
- **AC-3 (PRoot Command Construction):** `ProotRunner.buildLaunchCommand(profile, sessionRuntimeDir)` generates the exact arguments, pseudo mounts, KGSL node bindings, driver mounts, storage bindings, and environment variables defined by `StackProfile`.
- **AC-4 (Linker64 Wrapper Handling):** `ProotRunner` supports both standard executable invocation and `/system/bin/linker64` execution for `.so`-packaged PIE binaries on Android.
- **AC-5 (Rootfs Archive Extraction):** `RootfsExtractor` uncompresses tar archives without loading entire files into memory, preserves executable file modes, safely restores symlinks, and emits granular progress callbacks.
- **AC-6 (Process Execution & Stream Management):** `ProotProcessManager` handles process lifecycle, collects output streams asynchronously, reports exit codes, and provides cooperative cancellation.
- **AC-7 (Test Coverage & Gates):** Full suite of unit tests covers `RootfsManager`, `ProotRunner`, `RootfsExtractor`, and `ProotProcessManager` with 100% pass rate. `./gradlew test` and `./gradlew assembleDebug` pass with exit code 0.

---

## 3. Dependencies & Gates

- **Prerequisites:** TASK-002 (Done), TASK-003 (Done).
- **Implementation Gate:** Complete.
- **Verification Gate:** Complete (clean `./gradlew test` with 19 passing unit tests in `core:proot`, 39 total project unit tests, and `./gradlew assembleDebug` packaging all native libs).
- **Review Gate:** Complete (self-review against Dev Framework v0.2 and SRS v0.1).

---

## 4. Actual Changes & Evidence

- Extended `RuntimeDirectories` with properties for `prootLibPath`, `loaderPath`, `loader32Path`, `tallocLibPath`, `shmemLibPath`, and `ensureDirectoriesExist()`.
- Implemented `RootfsManager` with multi-rootfs directory isolation under `files/rootfs/<id>`, rootfs validity checking, snapshot cloning preserving symlinks and permissions, safe deletion with path traversal guards, Wayland session discovery (`.desktop` parsing), and provisioning markers.
- Implemented `RootfsExtractor` supporting streamed `.tar.gz`, `.tar.xz`, and `.tar` extraction with anti-ZipSlip protection, symlink and hard link restoration, executable permission setting, auto-configuration of `/etc/resolv.conf` and `/etc/hosts`, and progress reporting callbacks.
- Implemented `ProotRunner` assembling complete PRoot arguments (fake root `-0`, `--link2symlink`, `-r`, pseudo mounts, `/dev/kgsl-3d0`, XDG socket, Turnip drivers, selective SDCard mounts with Android/data masking, x86_64 FEX/box64 hooks, build cache env vars, and `/system/bin/linker64` wrapping).
- Implemented `ProotProcessManager` managing process lifecycle, background asynchronous stdout/stderr stream pumps, PID extraction, two-phase process termination, and coroutine execution.
- Vendored prebuilt native PRoot binaries (`lib_proot.so`, `lib_loader.so`, `lib_loader32.so`, `libtalloc.so`, `libandroid-shmem.so`, `libsandbox_redirect.so`, `libvfs_intercept.so`) in `core:proot/src/main/jniLibs/arm64-v8a/`.
- Authored 19 comprehensive unit tests across 4 test suites (`RootfsManagerTest`, `RootfsExtractorTest`, `ProotRunnerTest`, `ProotProcessManagerTest`) with 100% pass rate.
- Documented revision-bound verification evidence in [evidence/verification.md](evidence/verification.md).
