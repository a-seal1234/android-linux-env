# TASK-011 — Low-Latency PipeWire / PulseAudio Socket Bridge

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Implementation / Audio Subsystem & Low-Latency Bridge  
**Board:** [Central task board](../../../TASKS.md) is the only workflow-status source.  
**Owner / harness:** Codex / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Risk:** Standard (UNIX socket audio routing, AAudio/Oboe low-latency buffer management)  
**Source:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md) (REQ-AUDIO-001, REQ-AUDIO-002, REQ-AUDIO-003)

---

## 1. Outcome

Implement the low-latency PulseAudio / PipeWire audio bridge subsystem in `bridge:wayland` and `core:session`:
1. **Native Low-Latency Audio Server (AAudio via UNIX Domain Socket):**
   - Listens on a native UNIX domain socket (`$XDG_RUNTIME_DIR/pulse/native`), avoiding TCP loopback latency and port collisions.
   - Implements minimal PulseAudio protocol negotiation and audio streaming directly into Android's low-latency audio subsystem (`AAudio` / `AudioTrack`, 48000 Hz stereo float/PCM16).
   - Manages audio stream lifecycle on pause, resume, buffer underrun recovery, and stop.
2. **Guest PRoot Environment & Audio Socket Redirection:**
   - Injects `PULSE_SERVER=unix:/tmp/runtime-alw/pulse/native` (and `/run/user/1000/pulse/native`) and `PIPEWIRE_RUNTIME_DIR=/tmp/runtime-alw` into `EnvironmentGenerator` so both PulseAudio clients (SDL2, osu!lazer, Wine/FEX) and PipeWire applications route directly into the host Android sink without `/dev/snd` hardware nodes.
   - Configures `ArchProvisioningScriptBuilder` with client configuration (`/etc/pulse/client.conf`) pointing to the bridged UNIX socket without depending on D-Bus system services.
3. **Audio Bridge Manager & Diagnostics:**
   - Coordinates socket server lifecycle with `SessionController`.
   - Exposes active sample rate (48000 Hz), channels, buffer frames, latency metrics, and graceful null-sink fallback on headless/test hosts.

---

## 2. Planned Scope & Acceptance Criteria

- **AC-1 (UNIX Socket Audio Server):** Server listens at `$runtimeDir/pulse/native` and accepts guest audio clients over domain sockets.
- **AC-2 (Low-Latency Configuration):** Audio backend configures 48000 Hz, stereo, low-latency performance mode (`AAUDIO_PERFORMANCE_MODE_LOW_LATENCY` / minimal burst).
- **AC-3 (Environment Redirection):** `EnvironmentGenerator` exports `PULSE_SERVER` and `PIPEWIRE_RUNTIME_DIR` resolving to the active socket.
- **AC-4 (Arch Audio Client Provisioning):** Arch provisioning script writes client configuration directing PulseAudio apps to the bridged socket.
- **AC-5 (AudioBridgeManager & Tests):** Unit tests verify socket creation, lifecycle management, environment injection, and null-sink fallback.

---

## 3. Dependencies & Gates

- **Prerequisites:** TASK-007, TASK-010 (Completed).
- **Implementation Gate:** Passed (Implemented AudioBridgeServer in C++ with AAudio 48000Hz low-latency sink, AudioBridgeManager in Kotlin, environment injection, ProotRunner socket mounting, and Arch client provisioning).
- **Verification Gate:** Passed (All unit tests passed across all modules via `./gradlew test`; native build and debug APK assembled cleanly via `./gradlew assembleDebug`).
- **Review Gate:** Passed (Evidence recorded in [verification.md](evidence/verification.md)).

---

## 4. Verification Evidence

- [Verification Report](evidence/verification.md)
