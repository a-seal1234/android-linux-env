# TASK-003 Verification Evidence: session-core State Machine & StackProfile Engine

**Task:** [TASK-003 — session-core State Machine & StackProfile Engine](../TASK.md)  
**Date:** 2026-11-14  
**Verified By:** Codex (local Linux environment)  
**Status:** Verification Passed (Exit Code 0)  

---

## 1. Verified Components

1. **`StackProfile` JSON Engine:**
   - Full two-way deserialization (`StackProfile.fromJson`, `StackProfile.fromJsonString`) and serialization (`toJson`).
   - Implemented models for `DistroConfig`, `CompositorConfig`, `GpuConfig`, `DisplayConfig`, `StorageConfig`, `InputConfig`, and `EmulationConfig`.
   - Verified default configurations: `StackProfile.defaultArchXiaomiPad8()` and `StackProfile.safeModeProfile()`.
2. **`ProfileValidator`:**
   - Strictly validates profile IDs (`^[a-zA-Z0-9_-]+$`).
   - Strictly enforces the **Non-Destructive Invariant**: rejects profiles where `sessionConfigPath` points to `/home/` or `~` instead of `/tmp/`.
   - Rejects identical `userConfigPath` and `sessionConfigPath`.
   - Enforces storage safety: mandates exclusion of `/storage/emulated/0/Android/data` when `bindSdcard` is true to protect against Scoped Storage kernel deadlocks.
   - Enforces display parameter bounds (scale 0.5–4.0, refresh rate 30–240Hz).
3. **`ProfileRepository`:**
   - Safe atomic file persistence (temporary write + rename to `<id>.json`).
   - Loading and listing profiles from storage.
   - Profile deletion with protection preventing deletion of `safe-mode`.
   - `ensureDefaultProfiles()` bootstrap.
4. **`EnvironmentGenerator`:**
   - Generates complete Linux runtime environment variable maps.
   - Exports Turnip KGSL variables (`VK_ICD_FILENAMES`, `LIBGL_DRIVERS_PATH`, `LD_LIBRARY_PATH`, `MESA_LOADER_DRIVER_OVERRIDE=kgsl`, `TU_DEBUG=noconform`).
   - Exports Adreno proprietary + Zink variables (`MESA_LOADER_DRIVER_OVERRIDE=zink`, `adreno_icd.aarch64.json`).
   - Exports LLVMpipe Safe Mode variables (`LIBGL_ALWAYS_SOFTWARE=1`, `MESA_LOADER_DRIVER_OVERRIDE=llvmpipe`).
   - Exports build cache redirection variables (`CARGO_TARGET_DIR`, `UV_PROJECT_ENVIRONMENT`, `PNPM_STORE_DIR`, `GOCACHE`) targeting internal UFS storage.
   - Exports global Wayland flags (`ELECTRON_OZONE_PLATFORM_HINT`, `MOZ_ENABLE_WAYLAND`, `QT_QPA_PLATFORM`).
5. **`ConfigSynthesizer`:**
   - Generates non-destructive Hyprland session configs: `source = <userConfig>` executed first, followed by workstation display, scale, and `caps:super` keyboard overrides.
6. **`HyprlandIpcClient`:**
   - UNIX domain socket client connecting to `$XDG_RUNTIME_DIR/hypr/*.sock` to issue live `keyword <key> <value>` overrides.
7. **`SessionController`:**
   - Reactive state machine managing transitions across `SessionState`: `Idle -> Initializing -> Starting -> Running -> Stopping -> Stopped -> Error`.

---

## 2. Test Execution & Coverage

Executed via `./gradlew test`:
```text
> Task :core:session:testDebugUnitTest
StackProfileTest > testDefaultArchXiaomiPad8Profile PASSED
StackProfileTest > testSafeModeProfile PASSED
StackProfileTest > testConfigSynthesizerNonDestructiveWrapper PASSED
StackProfileTest > testJsonSerializationRoundTrip PASSED
StackProfileTest > testFromJsonString PASSED
ProfileValidatorTest > testValidDefaultProfiles PASSED
ProfileValidatorTest > testRejectInvalidId PASSED
ProfileValidatorTest > testRejectDestructiveHomeSessionConfig PASSED
ProfileValidatorTest > testRejectDangerousSdcardBindingWithoutExclusion PASSED
ProfileValidatorTest > testRejectInvalidDisplayBounds PASSED
ProfileRepositoryTest > testEnsureDefaultsAndList PASSED
ProfileRepositoryTest > testSaveAndGetCustomProfile PASSED
ProfileRepositoryTest > testRejectInvalidSave PASSED
ProfileRepositoryTest > testSafeModeCannotBeDeleted PASSED
ProfileRepositoryTest > testDeleteCustomProfile PASSED
EnvironmentGeneratorTest > testTurnipKgslEnvironment PASSED
EnvironmentGeneratorTest > testAdrenoProprietaryZinkEnvironment PASSED
EnvironmentGeneratorTest > testSafeModeLlvmpipeEnvironment PASSED
SessionControllerTest > testLifecycleTransitions PASSED
SessionControllerTest > testRejectInvalidProfilePreparation PASSED

BUILD SUCCESSFUL in 2s
```

All 20 unit tests in `:core:session` passed.
Combined test suite across `:core:session` and `:core:proot` passes with zero failures.

---

## 3. Build Assembly Verification

```bash
./gradlew assembleDebug
# Exit Code: 0 (BUILD SUCCESSFUL in 2s)
```
Generates:
- `core/session/build/outputs/aar/session-debug.aar`
- `app/build/outputs/apk/debug/app-debug.apk`
