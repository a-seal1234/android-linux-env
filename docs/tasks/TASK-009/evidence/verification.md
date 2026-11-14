# TASK-009 Verification Evidence — Shared Turnip Adreno 825 KGSL Driver Store & Node Binding

**Epic:** [EPIC-ALW-P2 — Phase 2](../../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Task:** [TASK-009](../TASK.md)  
**Date:** 2026-11-14  
**Author:** Codex / local Linux environment  

---

## 1. Overview of Delivered Subsystems

### 1.1 Shared External Driver Store (`files/drivers/turnip/<version>/`)
- Centralized driver installations under host `files/drivers/turnip/<version>/`, eliminating redundant driver duplication across multiple rootfs distributions.
- Mounted the active driver read-only into PRoot at `/opt/drivers/turnip/active:ro` (with backwards-compatible `/opt/turnip:ro` binding).
- Implemented `TurnipDriverManager` handling installation, integrity verification, activation, and removal.
- Managed `manifest.json` metadata records and `active-driver.txt` tracking.

### 1.2 GitHub Release Fetcher & SHA256 Verification
- Implemented `GitHubReleaseFetcher` with curated Mesa 25.0+ Turnip KGSL releases targeting Snapdragon 8s Gen 4 (Adreno 825 / A8xx).
- Implemented SHA256 cryptographic checksum calculation and validation:
  - Enforced checksum verification prior to archive unpacking.
  - Threw `SecurityException` upon hash mismatch, preventing corrupted or tampered driver binaries from executing.
- Handled `.tar.xz`, `.tar.gz`, `.tar`, `.zip`, and standalone `.so` archives with path traversal guards (anti-TarSlip / anti-ZipSlip).

### 1.3 ELF ABI Guard (`glibc` vs Android `Bionic`)
- Implemented `ElfAbiValidator` analyzing 64-bit ARM (`EM_AARCH64 = 183`) ELF binaries.
- Scanned dynamic string tables and runtime dependencies to distinguish:
  - `GLIBC_AARCH64`: Linked against GNU libc (`libc.so.6`, `GLIBC_2.*`, `ld-linux-aarch64.so.1`).
  - `BIONIC_ANDROID`: Linked against Android Bionic (`/system/bin/linker64`, `liblog.so`, `__android_log_print`).
- Rejected Bionic-linked binaries during Turnip driver installation to prevent silent segfaults and `glibc` loader crashes inside Arch Linux PRoot.

### 1.4 ICD Configuration Generation
- Implemented `IcdConfigGenerator` generating deterministic Vulkan Installable Client Driver JSON configurations:
  - `freedreno_icd.aarch64.json` with `"library_path": "/opt/drivers/turnip/active/libvulkan_freedreno.so"` and `"api_version": "1.4.0"`.
  - `adreno_icd.aarch64.json` with `"library_path": "/opt/drivers/turnip/active/libvulkan_adreno.so"`.

### 1.5 Zink OpenGL-over-Vulkan Pipeline & KGSL Node Fallback
- Updated `EnvironmentGenerator` and `ProotRunner` to inject:
  - `VK_ICD_FILENAMES=/opt/drivers/turnip/active/freedreno_icd.aarch64.json`
  - `VK_DRIVER_FILES=/opt/drivers/turnip/active/freedreno_icd.aarch64.json`
  - `LIBGL_DRIVERS_PATH=/opt/drivers/turnip/active/lib/dri`
  - `LD_LIBRARY_PATH=/opt/drivers/turnip/active/lib`
  - `MESA_LOADER_DRIVER_OVERRIDE=zink` and `GALLIUM_DRIVER=zink`
  - `TU_DEBUG=noconform` for early Adreno 825 bring-up compatibility.
- Implemented hardware device node `/dev/kgsl-3d0` accessibility check in `ProotRunner`:
  - When `/dev/kgsl-3d0` is accessible: binds node into container.
  - When `/dev/kgsl-3d0` is absent or unreadable: cleanly falls back to Mesa `llvmpipe` (`LIBGL_ALWAYS_SOFTWARE=1`, `MESA_LOADER_DRIVER_OVERRIDE=llvmpipe`, `GALLIUM_DRIVER=llvmpipe`), ensuring Hyprland and LabWC boot reliably without initialization crashes.

---

## 2. Test Execution & Evidence

### 2.1 Unit Tests in `core:proot`
Executed `./gradlew :core:proot:test`:
```text
> Task :core:proot:compileDebugUnitTestKotlin
> Task :core:proot:testDebugUnitTest
> Task :core:proot:compileReleaseUnitTestKotlin
> Task :core:proot:testReleaseUnitTest
> Task :core:proot:test

BUILD SUCCESSFUL in 4s
50 actionable tasks: 14 executed, 36 up-to-date
```
Tests verified:
- `ElfAbiValidatorTest`: Valid glibc aarch64 ELF detection, Bionic ELF rejection, x86_64 rejection, corrupted ELF rejection.
- `IcdConfigGeneratorTest`: Freedreno and Adreno proprietary ICD JSON generation and file persistence.
- `GitHubReleaseFetcherTest`: Curated release discovery, SHA256 computation, download checksum verification, and tampering rejection.
- `TurnipDriverManagerTest`: Archive installation, SHA256 validation, Bionic rejection, activation, and deletion.
- `ProotRunnerTest`: Target ICD path verification and `/dev/kgsl-3d0` fallback to LLVMpipe.

### 2.2 Full Workspace Test Suite
Executed `./gradlew test`:
```text
BUILD SUCCESSFUL in 4s
153 actionable tasks: 8 executed, 145 up-to-date
```
All 73 unit tests across all 4 modules (`:app`, `:core:session`, `:core:proot`, `:bridge:wayland`) passed.

### 2.3 Assemble Debug APK
Executed `./gradlew assembleDebug`:
```text
BUILD SUCCESSFUL in 1s
128 actionable tasks: 14 executed, 114 up-to-date
```
Clean build with exit code 0 and 0 warnings.
