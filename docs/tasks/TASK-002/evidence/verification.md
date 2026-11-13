# TASK-002 Verification Evidence: Multi-Module Scaffolding & Build Setup

**Task:** [TASK-002 — Scaffolding, Multi-Module Gradle & Toolchain Setup](../TASK.md)  
**Date:** 2026-11-13  
**Verified By:** Codex (local Linux environment)  
**Status:** Verification Passed (Exit Code 0)  

---

## 1. Multi-Module Scaffolding Verification

The project was structured into four decoupled modules:
1. **`:app`** (`com.alw.workstation`): Top-level Android application with Jetpack Compose Material 3 UI (`MainActivity`), `WorkstationApp`, and `WorkstationService` foreground service holding `PARTIAL_WAKE_LOCK`.
2. **`:core:session`** (`com.alw.workstation.core.session`): Core state machine (`SessionController`), `StackProfile` JSON model with non-destructive injection invariants, and `ConfigSynthesizer` for `/tmp/android-session.conf` generation.
3. **`:core:proot`** (`com.alw.workstation.core.proot`): Unrooted Linux container harness (`ProotRunner`), multi-rootfs directory management (`files/rootfs/<id>`), selective storage bind mounts (excluding `/Android/data`), UFS build cache redirection, and session discovery.
4. **`:bridge:wayland`** (`com.alw.workstation.bridge.wayland`): Embedded Wayland display server bridge adapting `Beauty114514/trierarch-app`, `ANativeWindow` presentation, `View.requestPointerCapture()` relative mouse handling, and hardware keyboard scancode remapping (`caps:super`).

---

## 2. Toolchain & Build Verification

The build was executed using the local toolchain:
- **Gradle:** 8.11.1
- **Android Gradle Plugin (AGP):** 8.9.1
- **Kotlin:** 2.1.0 with Compose Compiler plugin
- **Android SDK:** Compile SDK 35, Target SDK 35, Min SDK 29 (Android 10)
- **Android NDK:** r27c (`27.3.13750724`)
- **CMake:** 3.22.1 (installed in SDK) compiling C++20 (`-std=c++20`) native library `libwayland_bridge.so` for `arm64-v8a`.

### Build Commands & Exit Status
```bash
# 1. Full Multi-Module Debug Assembly
./gradlew assembleDebug
# Exit Code: 0 (BUILD SUCCESSFUL in 1s)

# 2. Unit Test Suite Execution
./gradlew test
# Exit Code: 0 (BUILD SUCCESSFUL in 2s, 147 actionable tasks)
```

---

## 3. Verified Artifacts

| Module | Artifact Path | Type | Architecture |
|---|---|---|---|
| `:app` | `app/build/outputs/apk/debug/app-debug.apk` | Android APK | `arm64-v8a` |
| `:bridge:wayland` | `bridge/wayland/build/outputs/aar/wayland-debug.aar` | Android AAR | `arm64-v8a` (`libwayland_bridge.so`) |
| `:core:proot` | `core/proot/build/outputs/aar/proot-debug.aar` | Android AAR | Pure JVM/Kotlin |
| `:core:session` | `core/session/build/outputs/aar/session-debug.aar` | Android AAR | Pure JVM/Kotlin |

### Native Library Inspection
Inspection of `app-debug.apk` confirmed native library packaging:
```text
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libwayland_bridge.so
-> 6168  lib/arm64-v8a/libwayland_bridge.so
```

---

## 4. Unit Test Coverage

- `StackProfileTest`:
  - `testDefaultArchXiaomiPad8Profile`: PASSED
  - `testSafeModeProfile`: PASSED
  - `testConfigSynthesizerNonDestructiveWrapper`: PASSED (verifies `source = ~/.config/hypr/hyprland.conf` before overrides)
  - `testJsonSerializationRoundTrip`: PASSED
- `ProotRunnerTest`:
  - `testBuildLaunchCommandArguments`: PASSED (verifies fake root `-0`, `--link2symlink`, `-r`, `-b /dev /proc /sys /dev/kgsl-3d0`, cache redirection `CARGO_TARGET_DIR`, `UV_PROJECT_ENVIRONMENT`, `PNPM_STORE_DIR`, `GOCACHE`, and Turnip KGSL flags)

---

## 5. Workspace Hygiene Audit

`.gitignore` and `.antigravityignore` were verified:
- `.gradle/` ignored
- `build/`, `*/build/`, `*/*/build/` ignored
- `rootfs/`, `files/rootfs/`, `files/drivers/`, `files/runtime/` ignored
- `local.properties` ignored
- No build outputs or cache files are tracked by Git.
