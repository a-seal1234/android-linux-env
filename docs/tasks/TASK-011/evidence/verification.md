# TASK-011 Verification Evidence — Low-Latency PipeWire / PulseAudio UNIX Socket Bridge

**Epic:** [EPIC-ALW-P2 — Phase 2](../../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Task:** [TASK-011](../TASK.md)  
**Date:** 2026-11-14  
**Author:** Codex / local Linux environment  

---

## 1. Overview of Delivered Subsystems

### 1.1 Native Low-Latency Audio Server (AAudio via UNIX Domain Socket)
- Implemented `AudioBridgeServer` in C++ (`bridge/wayland/src/main/cpp/audio_bridge.h` & `audio_bridge.cpp`):
  - Listens on a native UNIX domain socket (`$XDG_RUNTIME_DIR/pulse/native`), avoiding TCP loopback latency and port collisions.
  - Configures the Android `AAudio` backend for minimal latency:
    - 48000 Hz sample rate
    - 2 channels (stereo)
    - `AAUDIO_FORMAT_PCM_I16` (16-bit signed PCM)
    - `AAUDIO_PERFORMANCE_MODE_LOW_LATENCY`
    - `AAUDIO_SHARING_MODE_SHARED`
  - Implements lightweight PulseAudio native protocol negotiation:
    - `PA_COMMAND_AUTH`: exchanges protocol version (v35).
    - `PA_COMMAND_SET_CLIENT_NAME`: sets client index.
    - `PA_COMMAND_GET_SERVER_INFO`: returns server details (`pulseaudio 16.1`, sample spec 48000Hz stereo S16LE, default sink `alw_output`).
    - `PA_COMMAND_GET_SINK_INFO` / `PA_COMMAND_GET_SINK_INFO_LIST`: reports active audio sink with 5ms latency descriptor.
    - `PA_COMMAND_CREATE_PLAYBACK_STREAM`: allocates playback stream channel.
  - Streams incoming PCM audio packets directly into `AAudioStream_write` with underrun counting and volume scaling.
  - Provides graceful fallback to null-sink mode if AAudio is unavailable on headless or test host hardware.

### 1.2 Guest PRoot Environment & Audio Socket Redirection
- Updated `EnvironmentGenerator` in `core:session`:
  - Injected `PULSE_SERVER=unix:/run/user/1000/pulse/native unix:/tmp/runtime-alw/pulse/native`
  - Injected `PIPEWIRE_RUNTIME_DIR=/tmp/runtime-alw`
  - Routes both PulseAudio-targeting applications (SDL2, osu!lazer, Wine/FEX) and PipeWire clients directly into the host Android sink without `/dev/snd` hardware nodes or systemd-user daemons.
- Updated `ProotRunner` in `core:proot`:
  - Automatically creates and binds `${sessionRuntimeDir.absolutePath}` to both `/tmp/runtime-alw` and `/run/user/1000`, ensuring both socket paths resolve directly to the host UNIX domain socket.
- Updated `ArchProvisioningScriptBuilder` in `core:proot`:
  - Configures `/etc/pulse/client.conf` with `default-server = unix:/tmp/runtime-alw/pulse/native`, `autospawn = no`, `daemon-binary = /bin/true`, and `enable-shm = false` (preventing unrooted PRoot shared memory permission failures).
  - Configures symlink `/run/user/1000/pulse -> /tmp/runtime-alw/pulse` and global `/etc/environment` overrides.

### 1.3 Audio Bridge Manager & Session Lifecycle Coordination
- Implemented `AudioBridgeManager` in `com.alw.workstation.bridge.wayland.audio`:
  - Provides `startAudioServer(runtimeDir, sampleRate = 48000, channels = 2)` and `stopAudioServer()`.
  - Exposes `AudioStatus`: `isRunning`, `isNullSink`, `sampleRate`, `channels`, `socketPath`, `latencyMs`, and `underrunCount`.
  - Provides `coordinateWith(sessionController, runtimeDir, scope)` to automatically start audio on `SessionState.Running` and stop on `SessionState.Stopped` / `Idle` / `Error`.
  - Includes full null-sink mock mode for JVM test harness execution without NDK binaries.

---

## 2. Test Verification Results

### 2.1 Full Unit Test Suite (`./gradlew test`)
Executed `./gradlew test`:
```text
> Task :bridge:wayland:testDebugUnitTest
> Task :bridge:wayland:testReleaseUnitTest
> Task :core:proot:testDebugUnitTest
> Task :core:proot:testReleaseUnitTest
> Task :core:session:testDebugUnitTest
> Task :core:session:testReleaseUnitTest

BUILD SUCCESSFUL in 4s
153 actionable tasks: 26 executed, 127 up-to-date
```
Tests verified:
- `AudioBridgeManagerTest`:
  - `testAudioBridgeStartupAndShutdown`: verified socket file creation, status metrics, and clean shutdown.
  - `testVolumeClamping`: verified volume clamping to `[0.0, 1.0]`.
  - `testCoordinationWithSessionController`: verified automatic startup on `SessionState.Running` and stop on `SessionState.Stopped`.
  - `testCoordinationStopsOnError`: verified automatic shutdown on `SessionState.Error`.
- `EnvironmentGeneratorTest`:
  - Verified `PULSE_SERVER` containing `/run/user/1000/pulse/native` and `/tmp/runtime-alw/pulse/native`.
  - Verified `PIPEWIRE_RUNTIME_DIR=/tmp/runtime-alw`.
- `ArchProvisioningScriptBuilderTest`:
  - Verified `/etc/pulse/client.conf` generation with `default-server`, `enable-shm = false`, and symlinks.
  - Verified `configureAudioBridge = false` skipping.

### 2.2 Assemble Debug Build (`./gradlew assembleDebug`)
Executed `./gradlew assembleDebug`:
```text
> Task :bridge:wayland:configureCMakeDebug[arm64-v8a]
> Task :bridge:wayland:buildCMakeDebug[arm64-v8a]
> Task :app:assembleDebug

BUILD SUCCESSFUL in 2s
128 actionable tasks: 23 executed, 105 up-to-date
```
Compilation completed with 0 errors and 0 warnings.
