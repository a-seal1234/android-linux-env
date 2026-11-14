# TASK-009 — Shared Turnip Adreno 825 KGSL Driver Store & Node Binding

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Implementation / GPU Driver Subsystem & Hardware Acceleration  
**Board:** [Central task board](../../../TASKS.md) is the only workflow-status source.  
**Owner / harness:** Codex / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Risk:** Critical (Direct kernel KGSL ioctls, Adreno 825 hardware acceleration, driver integrity)  
**Source:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md) (REQ-GPU-001, REQ-GPU-002, REQ-GPU-003, REQ-GPU-004)

---

## 1. Outcome

Implement the shared Turnip GPU driver manager and direct KGSL node binding subsystem in `core:proot`:
1. **Shared External Driver Store (`files/drivers/turnip/<version>/`):**
   - Centralizes driver versions in a single shared store accessible read-only across all guest rootfs instances.
   - Eliminates redundant driver binaries across multiple distros, saving valuable UFS flash storage.
2. **Remote Release Fetching & Cryptographic Verification:**
   - Fetches verified Mesa Turnip `.tar.gz` driver archives from upstream GitHub releases and curated mirrors.
   - Computes and verifies SHA256 checksums prior to unpacking, preventing corrupted or tampered driver installations.
   - Automatically generates and validates `manifest.json` describing supported SoCs (Snapdragon 8s Gen 4 / Adreno 825) and required overrides.
3. **Adreno 825 Dual-Path Architecture:**
   - **Primary Path (`turnip_kgsl`):** Direct Mesa Turnip Vulkan 1.3 acceleration communicating directly with `/dev/kgsl-3d0`.
   - **Proprietary Backup Path (`adreno_proprietary_zink`):** Extracted Qualcomm proprietary `libvulkan_adreno.so` paired with Mesa Zink for OpenGL, providing immediate fallback if Turnip experiences A8xx regressions.
   - **Safe Mode Path (`llvmpipe`):** Mesa software rasterizer fallback for zero-GPU recovery.
4. **Dynamic Driver Binding & Environment Injection:**
   - Injects active driver paths via `VK_ICD_FILENAMES`, `LIBGL_DRIVERS_PATH`, `LD_LIBRARY_PATH`, and `MESA_LOADER_DRIVER_OVERRIDE`.
   - Mounts driver directories as read-only (`/opt/turnip:ro`) and `/dev/kgsl-3d0` into the PRoot container.

---

## 2. Planned Scope & Acceptance Criteria

- **AC-1 (Shared Store Architecture):** Drivers are organized under `files/drivers/turnip/<version>/` and mounted read-only into `/opt/turnip`.
- **AC-2 (Integrity Verification):** SHA256 checksum validation passes for verified downloads and rejects tampered archives.
- **AC-3 (Adreno 825 Dual-Path Support):** Supports importing both open-source Mesa Turnip builds and Qualcomm proprietary Adreno Vulkan drivers.
- **AC-4 (Direct KGSL Node Binding):** Validates `/dev/kgsl-3d0` node existence and generates correct PRoot bind arguments.
- **AC-5 (Unit & Integration Tests):** Driver manifest parsing, SHA256 verification, and driver store management unit tests pass.

---

## 3. Dependencies & Gates

- **Prerequisites:** TASK-007, TASK-008 (Completed).
- **Implementation Gate:** Passed. Shared Turnip driver store (`files/drivers/turnip/<version>/`), GitHub release client, SHA256 verification, ELF glibc vs Bionic validator, ICD generator, and KGSL fallback implemented.
- **Verification Gate:** Passed. 17 unit tests in `core:proot` (46 total in module, 73 project-wide) and full `./gradlew assembleDebug` passed.
- **Review Gate:** Passed. Evidence recorded in [evidence/verification.md](evidence/verification.md).
