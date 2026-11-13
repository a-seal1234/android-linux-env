# TASK-002 — Scaffolding, Multi-Module Gradle & Toolchain Setup

**Epic:** [EPIC-ALW-P1 — Phase 1](../../initiatives/android-linux-workstation/epics/phase-1-core-runtime-proot/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Multi-Module Android & Native Toolchain Scaffolding  
**Board:** [Central task board](../../../TASKS.md) is the only workflow-status source.  
**Owner / harness:** Codex / local Linux shell  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Risk:** Standard (Scaffolding, build configuration, module structure)  
**Source:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md)  

---

## 1. Outcome

Initialize a complete, clean-compiling Android multi-module project spanning four decoupled modules:
- `:app`: Top-level Android application module (`com.alw.workstation`) with Compose UI, activity, service declarations.
- `:core:session`: Session orchestration, state machine, `StackProfile` schema & injection engine.
- `:core:proot`: PRoot runner engine, multi-rootfs directory lifecycle, bind mounts, and execution harness.
- `:bridge:wayland`: Embedded Wayland display server, `ANativeWindow` presentation bridge, pointer capture, and audio socket forwarding (adapted from `Beauty114514/trierarch-app`).

Deliver a working Gradle 8.11.1 / AGP build where `./gradlew assembleDebug` compiles cleanly across all four modules.

---

## 2. Refinements Incorporated

1. **Vendor/Adapt Wayland & Audio Bridge:** Structure `bridge/wayland` to adapt the embedded Wayland server and PulseAudio/PipeWire socket architecture from `Beauty114514/trierarch-app` rather than writing raw `libwayland-server` bindings from scratch.
2. **Adreno 825 (A8xx) Driver Flexibility:** Design driver library models to support both open-source Mesa Turnip `.tar.gz` packages and proprietary Qualcomm `libvulkan_adreno.so` (+ Zink OpenGL) alongside `llvmpipe` Safe Mode.
3. **Workspace Hygiene:** Add `.gradle/`, `build/`, and local `rootfs/` test directories to `.gitignore` and `.antigravityignore`.

---

## 3. Planned Scope & Acceptance Criteria

- **AC-1:** Root `settings.gradle.kts` and `build.gradle.kts` configured with Gradle 8.11.1, AGP, Kotlin 2.1.0, Compose Compiler.
- **AC-2:** `gradle/wrapper/` configured with `gradle-8.11.1-bin.zip`.
- **AC-3:** `local.properties` pinned to `/home/seal/toolchain/android-sdk` and NDK `27.3.13750724`.
- **AC-4:** Four submodules (`:app`, `:core:session`, `:core:proot`, `:bridge:wayland`) configured with clean dependency separation.
- **AC-5:** Initial source files and interfaces stubbed:
  - `core:session`: `StackProfile`, `SessionController`, `SessionState`.
  - `core:proot`: `ProotRunner`, `BindMount`, `RootfsManager`.
  - `bridge:wayland`: `WaylandSurfaceView`, `WaylandServerManager`, `InputDispatcher`.
  - `app`: `MainActivity`, `WorkstationApp`, `WorkstationService`.
- **AC-6:** `./gradlew assembleDebug` succeeds with exit code 0.

---

## 4. Dependencies & Gates

- **Prerequisites:** TASK-001 (Approved).
- **Implementation Gate:** Complete.
- **Verification Gate:** Complete (clean `./gradlew assembleDebug` and `./gradlew test` with 5 passing unit tests).
- **Review Gate:** Complete (self-review against Dev Framework v0.2 criteria).

---

## 5. Actual Changes & Evidence

- Created Gradle wrapper (`gradlew`, `gradle/wrapper/gradle-wrapper.jar`, `gradle-wrapper.properties`).
- Configured root `settings.gradle.kts` and `build.gradle.kts`.
- Configured `.gitignore` and `.antigravityignore` for fast indexing and clean workspace hygiene.
- Implemented `:core:session`: `StackProfile`, `SessionController`, `ConfigSynthesizer`, and unit test suite.
- Implemented `:core:proot`: `ProotRunner`, `BindMount`, `RootfsManager`, `RuntimeDirectories`, and unit test suite.
- Implemented `:bridge:wayland`: `WaylandServerManager`, `WaylandSurfaceView`, `InputDispatcher`, CMake build setup, and C++20 JNI bridge `wayland_bridge.cpp`.
- Implemented `:app`: `MainActivity` (Compose UI), `WorkstationApp`, `WorkstationService` (Foreground Service with `PARTIAL_WAKE_LOCK`), and Android manifest.
- Verified `./gradlew assembleDebug` produces `app-debug.apk` containing `libwayland_bridge.so` for `arm64-v8a` and all AARs.
- Detailed verification report in [evidence/verification.md](evidence/verification.md).
