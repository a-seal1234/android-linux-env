# TASK-006 — Selective Storage Bridge & Build Cache Redirection

**Epic:** [EPIC-ALW-P1 — Phase 1](../../initiatives/android-linux-workstation/epics/phase-1-core-runtime-proot/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Implementation / Storage & Build System Architecture  
**Board:** [Central task board](../../../TASKS.md) is the only workflow-status source.  
**Owner / harness:** Codex / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Risk:** High Risk (Scoped Storage access restrictions, kernel FUSE deadlocks, build cache throughput)  
**Source:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md) (REQ-STORAGE-001, REQ-STORAGE-002, REQ-STORAGE-003, REQ-PROOT-005)

---

## 1. Outcome

Implement the selective storage bridge and high-speed UFS build cache subsystem in `core:proot`:
1. **Selective Storage Bridge (`StorageBridgeManager`):**
   - Binds host `/storage/emulated/0` to guest `/mnt/sdcard`.
   - Strictly enforces Scoped Storage protection by masking forbidden directories (`Android/data` and `Android/obb`) with empty directory mounts to prevent kernel `EACCES` and FUSE driver hangs.
   - Detects external storage media (SD cards under `/storage/<UUID>`, USB OTG drives).
   - Validates path safety, rejecting attempts to expose private Android app sandboxes or host system partitions directly.
2. **High-Speed UFS Build Cache Manager:**
   - Pre-initializes build cache directories in guest high-speed internal UFS storage: `/home/<user>/.cache/` (`cargo-target`, `uv-env`, `pnpm-store`, `go-build`, `ccache`, `gradle-user-home`).
   - Ensures correct ownership and permissions for the non-root user.
   - Validates build cache environment redirection exports (`CARGO_TARGET_DIR`, `UV_PROJECT_ENVIRONMENT`, `PNPM_STORE_DIR`, `GOCACHE`, `GRADLE_USER_HOME`).
3. **Soft-Symlink Emulation Layer (`--link2symlink` / `--symlink2file`):**
   - Integrates soft-symlink emulation on non-POSIX shared storage filesystems (sdcardfs / FUSE).
   - Simulates symlinks on shared storage using header files (`!#alw-symlink\n<target>`).
4. **Integration with `ProotRunner`:**
   - Connects `StorageBridgeManager` into `ProotRunner.buildLaunchCommand` to assemble all storage binds, masking binds, and cache environment variables.

---

## 2. Planned Scope & Acceptance Criteria

- **AC-1 (Selective SDCard Mounts):** `StorageBridgeManager.resolveStorageMounts(profile)` generates bind mounts for `/storage/emulated/0` to `/mnt/sdcard`.
- **AC-2 (Scoped Storage Protection):** Automatically injects masking mounts for `/mnt/sdcard/Android/data` and `/mnt/sdcard/Android/obb` pointing to an empty, isolated host directory.
- **AC-3 (Build Cache Initialization):** `StorageBridgeManager.ensureCacheDirectoriesExist(rootfsDir, defaultUser)` initializes internal UFS cache directories with proper permissions.
- **AC-4 (Environment Redirection):** Verifies that `EnvironmentGenerator` and `ProotRunner` redirect `CARGO_TARGET_DIR`, `UV_PROJECT_ENVIRONMENT`, `PNPM_STORE_DIR`, `GOCACHE`, `GRADLE_USER_HOME` to internal UFS storage when enabled.
- **AC-5 (Symlink2File Emulation):** Confirms `--link2symlink` CLI argument injection when `enableSymlink2File` is true.
- **AC-6 (Unit & Integration Tests):** Full suite of unit tests covers `StorageBridgeManager` with 100% pass rate. `./gradlew test` and `./gradlew assembleDebug` pass with exit code 0.

---

## 3. Dependencies & Gates

- **Prerequisites:** TASK-004 (Done).
- **Implementation Gate:** Complete.
- **Verification Gate:** Complete (clean `./gradlew test` with 5 passing unit tests for TASK-006, 49 total project unit tests, and `./gradlew assembleDebug` passing).
- **Review Gate:** Complete (self-review against Dev Framework v0.2 and SRS v0.1).

---

## 4. Actual Changes & Evidence

- Implemented `StorageBridgeManager` managing selective storage mounts, Scoped Storage masking, build cache pre-allocation, and external media discovery.
- Configured masking mounts for `/storage/emulated/0/Android/data` and `/storage/emulated/0/Android/obb` pointing to isolated empty directory, avoiding kernel FUSE lockups.
- Configured build cache redirection exports (`CARGO_TARGET_DIR`, `UV_PROJECT_ENVIRONMENT`, `PNPM_STORE_DIR`, `GOCACHE`, `CCACHE_DIR`, `GRADLE_USER_HOME`) targeting internal UFS storage.
- Integrated `StorageBridgeManager` into `ProotRunner.buildLaunchCommand` and updated `EnvironmentGenerator`.
- Created `StorageBridgeManagerTest` with 5 passing unit tests covering disabled mounts, masking, cache environments, directory pre-allocation, and overall resolution.
- Documented revision-bound verification evidence in [evidence/verification.md](evidence/verification.md).
