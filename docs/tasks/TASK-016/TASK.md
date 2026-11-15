# TASK-016 — Optional x86_64 FEX/box64 Translation Hook (-q)

**Epic:** [EPIC-ALW-P3 — Phase 3](../../initiatives/android-linux-workstation/epics/phase-3-onboarding-ui-integrations/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Implementation / Engineering Task  
**Board:** [Central task board](../../../TASKS.md) is the only workflow-status source.  
**Owner / harness:** Codex / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Risk:** High Risk  
**Source:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md) — REQ-X86-001, REQ-X86-002, REQ-X86-003  

---

## 1. Outcome

Implement an optional, zero-overhead x86_64 binary translation subsystem supporting both FEX-Emu (`FEXInterpreter`) and box64 via PRoot's transparent `-q` parameter hook. The implementation manages emulator overlays centrally in a shared host store (`files/overlays/fex/<version>/` and `files/overlays/box64/<version>/`) to avoid per-workspace disk duplication, enforces strict SHA256 and 64-bit ARM (`EM_AARCH64` + `glibc`) ELF validation, bind-mounts the active emulator overlay read-only into PRoot at `/opt/overlays/<engine>/active/`, configures Vulkan and OpenGL thunk environment variables (`FEX_THUNKHOSTPATH`, `FEX_THUNKGUESTPATH`, `BOX64_LD_LIBRARY_PATH`) so translated x86_64 applications utilize Turnip KGSL Adreno 825 GPU acceleration, and exposes emulator configuration controls in the Stack Configurator and Shared Store UI.

---

## 2. Source Requirements

- **REQ-X86-001 (Transparent PRoot Hook):** Runtime provides an optional `-q` parameter hook for PRoot pointing to `FEXInterpreter` or `box64`.
- **REQ-X86-002 (Zero Overhead on Native aarch64):** Foreign binary translation invoked strictly when executing `x86_64` ELFs. Native `aarch64` binaries execute directly on host ARM cores with zero emulation overhead when emulation is disabled or executing native binaries.
- **REQ-X86-003 (Vulkan & GL Thunking):** When using `FEXInterpreter` or `box64`, system mounts/points to Vulkan and OpenGL thunk libraries allowing x86_64 applications direct access to the Adreno Turnip driver.

---

## 3. Planned Scope & Acceptance Criteria

1. **Shared x86_64 Emulator Store (`files/overlays/fex/<version>/` and `files/overlays/box64/<version>/`):**
   - Host `aarch64` emulator binaries (`FEXInterpreter`, `box64`) and x86_64 rootfs/thunk overlays managed centrally in `files/overlays/`.
   - Verified via SHA256 checksum and `ElfAbiValidator` confirming 64-bit ARM (`EM_AARCH64`) with `GLIBC_AARCH64` linkage (preventing Bionic-linked binaries).
   - Atomic active link (`files/overlays/<engine>/active`) pointing to the selected version.
   - Read-only bind-mount into PRoot at `/opt/overlays/<engine>/active:ro`.
2. **PRoot `-q` & Environment Integration:**
   - `ProotRunner` injects `-q /opt/overlays/fex/active/bin/FEXInterpreter` (or `box64`) when emulation is enabled.
   - Omitted completely when disabled, ensuring native `aarch64` execution has zero overhead.
   - `EnvironmentGenerator` sets thunking variables:
     - FEX: `FEX_THUNKHOSTPATH`, `FEX_THUNKGUESTPATH`, `FEX_ROOTFS`.
     - box64: `BOX64_LD_LIBRARY_PATH`, `BOX64_PATH`, `BOX64_LOG`, `BOX64_NOBANNER`.
3. **UI Integration:**
   - `StackConfiguratorScreen` & `StackConfiguratorViewModel`: Expose x86_64 Translation Hook toggle, engine selector (`FEX` vs `BOX64`), and thunk libraries toggle.
   - `DriverStoreScreen` & `DriverStoreViewModel`: Manage and inspect installed emulator overlays.
4. **Verification & Quality:**
   - Unit tests covering `EmulatorManager`, `ProotRunner` `-q` injection, `EnvironmentGenerator` thunking vars, and `StackConfiguratorViewModel` state transitions.
   - `./gradlew test` and `./gradlew assembleDebug` pass with 0 errors.

---

## 4. Dependencies & Gates

- **Prerequisites:** TASK-004 (PRoot harness), TASK-009 (Turnip driver store).
- **Implementation Gate:** Completed.
- **Verification Gate:** Passed. Full test suite passing (159+ tests), clean `./gradlew assembleDebug`.
- **Review Gate:** Complete. Revision-bound verification evidence recorded in [evidence/verification.md](evidence/verification.md).

---

## 5. Implementation Summary

1. **`core:session`:**
   - Extended [StackProfile.kt](../../../core/session/src/main/java/com/alw/workstation/core/session/model/StackProfile.kt) with `EmulationConfig` (`enabled`, `engine`, `thunkLibsEnabled`, `overlayVersion`, `customInterpreterPath`) and two-way JSON serialization.
   - Enhanced [EnvironmentGenerator.kt](../../../core/session/src/main/java/com/alw/workstation/core/session/EnvironmentGenerator.kt) to configure `FEX_THUNKHOSTPATH`, `FEX_THUNKGUESTPATH`, `FEX_ROOTFS`, and `BOX64_LD_LIBRARY_PATH`.
2. **`core:proot`:**
   - Extended [RuntimeDirectories.kt](../../../core/proot/src/main/java/com/alw/workstation/core/proot/RuntimeDirectories.kt) with `files/overlays/` paths.
   - Implemented [EmulatorManifest.kt](../../../core/proot/src/main/java/com/alw/workstation/core/proot/emulator/EmulatorManifest.kt) and [EmulatorRelease.kt](../../../core/proot/src/main/java/com/alw/workstation/core/proot/emulator/EmulatorRelease.kt).
   - Implemented [EmulatorManager.kt](../../../core/proot/src/main/java/com/alw/workstation/core/proot/emulator/EmulatorManager.kt) for archive extraction, SHA256 verification, and `ElfAbiValidator` GLIBC_AARCH64 enforcement.
   - Updated [ProotRunner.kt](../../../core/proot/src/main/java/com/alw/workstation/core/proot/ProotRunner.kt) to bind-mount `/opt/overlays/<engine>/active:ro` and inject `-q` transparently with zero overhead on native binaries.
3. **`app`:**
   - Updated [StackConfiguratorScreen.kt](../../../app/src/main/java/com/alw/workstation/ui/configurator/StackConfiguratorScreen.kt) with Section 7 x86_64 Translation Hook controls.
   - Updated [DriverStoreScreen.kt](../../../app/src/main/java/com/alw/workstation/ui/driverstore/DriverStoreScreen.kt) with GPU Drivers vs x86_64 Emulators tabbed navigation.
   - Added unit test suites verifying all components.
