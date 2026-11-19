# TASK-033 — Low-Latency Audio & Microphone Bridge (`PipeWire` ↔ `AAudio`)

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Native Audio Backend, Guest PipeWire Integration & Permission Flow  
**Board:** [Central task board](../../../TASKS.md) is the workflow-status source.  
**Owner / harness:** Antigravity / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Write scope:** `bridge/wayland/` (native audio backend), `core/session/`, `core/proot/`, `app/`, `docs/tasks/TASK-033/`  
**Risk:** High  
**Status:** **Planned** — spec registered; implementation starts after TASK-026/027/028 are complete.  
**Depends on:** TASK-025 (`alw-host.sock`), TASK-011 (existing AAudio output + PulseAudio native-protocol server), TASK-019 (`/run/user/1000` fakefs bind).  
**Fallback preserved:** the existing output-only `AudioBridgeServer` (AAudio `DIRECTION_OUTPUT`, null-sink fallback) stays the shipped path. Microphone capture is additive and opt-in per client; if `RECORD_AUDIO` is denied or AAudio input fails, the guest source exists but produces silence, and playback is unaffected.

---

## 1. Problem Statement

TASK-011 delivered an AAudio **output** backend (`bridge/wayland/src/main/cpp/audio_bridge.cpp`)
speaking the PulseAudio native protocol on `$XDG_RUNTIME_DIR/pulse/native`, with
`PULSE_SERVER=unix:/run/user/1000/pulse/native` exported by `EnvironmentGenerator`. What is missing:

| Area | Current state | Gap |
|---|---|---|
| Output | AAudio output stream, `AAUDIO_PERFORMANCE_MODE_LOW_LATENCY`, PCM I16 48 kHz stereo | Works, but the wire path is the full PulseAudio native protocol and there is no dedicated low-latency PCM channel |
| Input | None | No `AAUDIO_DIRECTION_INPUT` stream; no capture source in the guest |
| Socket | `$XDG_RUNTIME_DIR/pulse/native` (and `/run/user/1000/pulse/native`) | No `$XDG_RUNTIME_DIR/alw-audio.sock`; no explicit duplex contract |
| Guest integration | `PIPEWIRE_RUNTIME_DIR=/tmp/rt`; Arch audio client configs provisioned | No PipeWire/PulseAudio module that maps a guest sink/source to the bridge |
| Permissions | Manifest has no `RECORD_AUDIO`; no runtime request | Microphone is unreachable |
| Lifecycle | `AudioBridgeManager.coordinateWith(SessionController)` | Capture is tied to playback only; no per-client capture start/stop, no privacy indicator |

## 2. Architecture — duplex low-latency audio

```
┌──────────────────────── guest (Hyprland) ────────────────────────────────────┐
│  PipeWire / pipewire-pulse                                                    │
│    module-simple-protocol-unix (playback)  ▸ writes S16LE 48k stereo frames   │
│    module-simple-protocol-unix / -pipe-source (capture) ▸ reads mic frames    │
│    (verify the exact module + framing against the installed version; fallback │
│     to module-pipe-sink / module-pipe-source FIFOs)                            │
│  Applications: Firefox, mpv, Audacity, arecord                                │
└──────────────┬───────────────────────────────────────────────────────────────┘
               │  $XDG_RUNTIME_DIR/alw-audio.sock  (SOCK_STREAM, raw PCM)
┌──────────────▼──────────── Android host (audio_bridge.cpp) ───────────────────┐
│  AudioBridgeServer                                                            │
│    output: AAudioStream DIRECTION_OUTPUT  (existing)                          │
│    input : AAudioStream DIRECTION_INPUT   (new, low-latency, VOICE_COMMUNICATION│
│             or UNPROCESSED — verify which the device accepts)                 │
│    duplex PCM channel: header handshake {magic, version, format, rate, ch}    │
│      then independent playback (host→AAudio) and capture (AAudio→host) rings  │
│    metrics: latencyMs, underruns, capture state → AudioStatus                 │
│  lifecycle: bound to SessionController; capture stops on session stop         │
└──────────────┬────────────────────────────────────────────────────────────────┘
               │
        Android audio HAL / speaker / microphone
```

### 2.1 Wire contract

- `$XDG_RUNTIME_DIR/alw-audio.sock`, `SOCK_STREAM`, short path (`/tmp/rt/alw-audio.sock`), mode
  `0660`/`0770` consistent with the other session sockets.
- Fixed handshake (little-endian): magic, version, sample format (S16LE), sample rate (48 kHz),
  channels (2). Reject unknown versions rather than misinterpreting bytes.
- Playback and capture are independent directions over the same connection; a capture-only client
  must not block playback and vice versa. Sane buffering with explicit underrun/overrun counters.
- No magic timing constants: derive buffer sizing from `AAudioStream_getFramesPerBurst()` /
  `getBufferSizeInFrames()` and adapt to the device, in line with the project's VSync/pacing rule.

### 2.2 Guest integration

- Do **not** guess the module name or framing. Verify against the installed PipeWire/PulseAudio
  version in the guest image (module availability, CLI arguments, raw-PCM framing) before coding;
  record the exact version in evidence.
- Preferred: `module-simple-protocol-unix` playback + a matching capture module. Fallback:
  `module-pipe-sink` / `module-pipe-source` with the two FIFOs created under `/tmp/rt/`.
- Provision the module loading through `ConfigSynthesizer`'s `hyprland.start` autostart or a
  `pipewire.conf.d` drop-in, non-destructively (only create when absent).
- Keep `PULSE_SERVER`/`PIPEWIRE_RUNTIME_DIR` behaviour working; `DISPLAY`-style additive changes.

### 2.3 Permission and privacy

- `app/` manifest adds `RECORD_AUDIO` (`android.permission.RECORD_AUDIO`); request it at runtime
  only when the guest first opens a capture source (observable via the first `AAudio` input
  open attempt / a control poke from the guest), with a clear rationale.
- Denial → the source is created but silence flows; playback is never blocked.
- Capture must stop when the session stops / app goes background for an extended period; show an
  in-session indicator while the microphone is live.

## 3. Phases

### Phase 0 — Verify the installed audio stack and establish the contract
- Inspect the guest PipeWire/PulseAudio version and available modules; capture `pactl list
  modules` / `pipewire --version`.
- Confirm AAudio input support and the best-performing input mode on the Xiaomi Pad 8.
- Freeze the `alw-audio.sock` framing and document it.

### Phase 1 — AAudio input stream + permission flow
- Add `AAudioStream` `DIRECTION_INPUT` creation, start/stop, and error handling; keep the
  null-source fallback.
- Add the `RECORD_AUDIO` manifest entry and runtime request path; wire the denial path.
- Extend `AudioStatus` with capture state and input latency.

### Phase 2 — Duplex socket backend
- Implement the handshake, playback and capture rings in `audio_bridge.cpp`; keep the existing
  PulseAudio native server behaviour intact for TASK-011 clients.
- Expose start/stop via new JNI in `AudioBridgeManager`; extend `coordinateWith` for capture.
- Add `$XDG_RUNTIME_DIR/alw-audio.sock` to `RuntimeDirectories`/`EnvironmentGenerator`.

### Phase 3 — Guest module integration
- Provision and load the verified playback/capture modules; implement the FIFO fallback.
- Confirm `arecord`/Audacity capture and `mpv`/Firefox playback through the guest stack.

### Phase 4 — Latency, lifecycle and tests
- Tune buffer sizes from AAudio burst metrics; measure end-to-end latency and underruns.
- Stop capture on session stop; add unit tests for the handshake/format negotiation and the
  permission state machine; record evidence.

## 4. Acceptance Criteria

| Criterion | Description | Status |
|---|---|---|
| **AC-01** | Task registered with architecture, phases and this AC table | **Done** |
| **AC-02** | Existing guest playback still works through the AAudio output path (regression) | Planned |
| **AC-03** | Guest microphone capture reaches a guest app (`arecord`/Audacity) with recorded evidence and a measured end-to-end latency | Planned |
| **AC-04** | `RECORD_AUDIO` is requested on demand; denial yields silence, never a crash or blocked playback | Planned |
| **AC-05** | `$XDG_RUNTIME_DIR/alw-audio.sock` handshake rejects wrong versions/formats and survives disconnect | Planned |
| **AC-06** | Capture stops on session stop / long backgrounding; an in-session indicator is shown while live | Planned |
| **AC-07** | The verified module path works; the FIFO fallback also passes when the preferred module is absent | Planned |
| **AC-08** | Unit tests + `./gradlew assembleDebug` green; evidence in `docs/tasks/TASK-033/evidence/`; commit pushed to `origin/main` | Planned |

## 5. Verification Plan

1. Baseline: play audio in the guest; confirm output through the AAudio path.
2. Input: `arecord -f S16_LE -r 48000 -c 2 /tmp/test.wav`, play it back; Audacity capture.
3. Permission: first capture attempt triggers the runtime request; deny → silence; grant → audio.
4. Latency: measure with a loopback/clap test and the bridge's counters; record underruns.
5. Lifecycle: stop the session during capture; confirm the stream closes and the mic indicator
   clears.
6. Fallback: remove the preferred module; restart; FIFO path still works.

## 6. Risks & Mitigations

- **Guest module/version drift** → verify the exact installed module and framing before coding
  (project rule for third-party APIs); ship the FIFO fallback and record versions in evidence.
- **AAudio input mode rejection** → probe `VOICE_COMMUNICATION` vs `UNPROCESSED` vs default;
  fall back gracefully.
- **Capture privacy** → opt-in per client, explicit indicator, stop on session stop/background.
- **Buffer/latency regressions on output** → keep the existing output stream path; new duplex
  logic is additive and behind the new socket, not a rewrite of the output stream.
- **Socket path length under PRoot** → `alw-audio.sock` lives under `/tmp/rt`, verified short.
