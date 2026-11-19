# TASK-032 — On-Demand Rootless XWayland (`xwayland-satellite`)

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Session Orchestration, Guest Provisioning & X11 Compatibility  
**Board:** [Central task board](../../../TASKS.md) is the workflow-status source.  
**Owner / harness:** Antigravity / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Write scope:** `core/session/`, `core/proot/`, `docs/tasks/TASK-032/`  
**Risk:** Medium  
**Status:** **Planned** — spec registered; implementation starts after TASK-026/027/028 are complete.  
**Depends on:** TASK-026 (Hyprland session, `ConfigSynthesizer`, render-scale plumbing), TASK-024 (compositor bring-up), TASK-021 (guest provisioning).  
**Fallback preserved:** `xwayland.enabled = false` remains the compositor default. If `xwayland-satellite` is unavailable or incompatible, the session simply has no XWayland (today's behaviour). The LabWC Safe Mode profile is untouched.

---

## 1. Problem Statement

`ConfigSynthesizer.synthesizeHyprlandSessionConfig()` hardcodes
`xwayland = { enabled = false }` in the generated Lua override. That keeps Hyprland's start-up
fast and avoids the known XWayland start-up cost, but it also means any legacy X11-only client —
KiCad, Java/Swing tooling, older Electron/GTK2 apps — cannot start at all:

```
[guest] $ kicad
Failed to connect to X server / cannot open display
```

Simply flipping the flag to `true` starts XWayland eagerly on every session, adding a fixed boot
cost and a process that most sessions never use. The requirement is **on demand**: no XWayland
process at idle, spawned automatically the first time an X11 client connects.

## 2. Architecture — socket-activated rootless XWayland

```
┌──────────────────────── guest (Arch Linux ARM, PRoot) ───────────────────────┐
│  Hyprland (Wayland, xwayland.enabled = false)                                 │
│  autostart: `alw-xwayland` → xwayland-satellite :0                            │
│      (idle: holds /tmp/.X11-unix/X0 only, no Xwayland process)                │
│                                                                               │
│  X11 client (KiCad, Swing, …) ── connect DISPLAY=:0 ──► socket accept         │
│      → xwayland-satellite spawns `Xwayland :0 -rootless -terminate …`         │
│      → X windows mapped as native Wayland toplevels                            │
└──────────────┬────────────────────────────────────────────────────────────────┘
               │  existing nested Wayland path (wayland_bridge.cpp)
┌──────────────▼──────────── Android host ──────────────────────────────────────┐
│  unchanged bridge; no new protocol globals unless the satellite requires them  │
└───────────────────────────────────────────────────────────────────────────────┘
```

### 2.1 Provisioning and environment

- `ArchProvisioningScriptBuilder`: add `xorg-xwayland` (required) and the satellite package.
  Verify the exact package source for Arch ARM (`extra` vs AUR) and pin nothing implicitly — the
  provisioning script must fail soft when the satellite package is unavailable, leaving
  `xorg-xwayland` installed for the fallback path.
- `EnvironmentGenerator`: add `DISPLAY=:0` for the guest. It is harmless when no X server is
  running and lets X11 clients attempt the socket.
- `ConfigSynthesizer`: keep `xwayland = { enabled = false }`; add an `hl.on("hyprland.start")`
  autostart that launches the satellite only if the binary exists. The satellite must be idle-
  cheap; verify it does not spawn Xwayland until the socket is connected.
- Socket path: `/tmp/.X11-unix/X0` is short enough for `sockaddr_un.sun_path` under PRoot. Do not
  place it under a deep translated path (the TASK-024 `sun_path` lesson).
- Non-destructive: never overwrite a user-provided `DISPLAY`, autostart entry or compositor
  override.

### 2.2 Feasibility gate (time-boxed spike)

`xwayland-satellite` targets wlroots-style compositors. Before wiring it in, run it against the
bridge with `WAYLAND_DEBUG=1` and record which globals/versions it requires (e.g. layer-shell,
foreign-toplevel, xwayland-keyboard-grab). **Bail-out rule:** if it needs protocol support the
bridge does not expose and adding that support is not a small, contained change, fall back to a
plain lazily started `Xwayland :0 -rootless -terminate` shim (started by the first client wrapper)
and leave satellite support as a documented follow-up — do not expand this task into a second
protocol project.

## 3. Phases

### Phase 0 — Feasibility spike (time-boxed)
- Install `xorg-xwayland` + candidate satellite in a scratch guest; run under the bridge with
  `WAYLAND_DEBUG=1`; capture the required-globals list and the exact failure mode.
- Measure idle cost and first-client latency. Record the decision (satellite vs plain Xwayland).

### Phase 1 — Provisioning and environment
- Add the packages to the provisioning package set (soft-fail the satellite).
- `EnvironmentGenerator`: `DISPLAY=:0`.
- `ConfigSynthesizer`: keep `xwayland.enabled=false`, add the guarded autostart.
- Unit-test the generated Lua/env for the expected lines (non-destructive assertions).

### Phase 2 — Launcher integration
- Add the `alw-xwayland` shim (or `xwayland-satellite` autostart) that verifies the compositor
  socket and only then starts the satellite.
- Ensure the satellite retries/recovers if the compositor socket is not yet present at
  `hyprland.start` (ordered exec + short bounded retry driven by socket existence, not a fixed
  magic sleep).
- Verify no XWayland process exists at idle: `pgrep -a Xwayland` is empty after boot.

### Phase 3 — Compatibility verification
- KiCad (X11 build), a Java/Swing app, and a legacy Electron/GTK2 app: window maps, input works,
  scaling is sane at the render scale in use.
- Confirm Wayland-native apps are unaffected and `DISPLAY=:0` does not change their toolkit
  selection.
- Measure session start-up: must be unchanged within noise versus TASK-026's baseline.

### Phase 4 — Tests, evidence and rollback
- Unit tests for the generated config/env + provisioning package list.
- Document the one-line rollback (remove the autostart) and the satellite-vs-plain decision.

## 4. Acceptance Criteria

| Criterion | Description | Status |
|---|---|---|
| **AC-01** | Task registered with architecture, phases and this AC table | **Done** |
| **AC-02** | Feasibility spike executed and the satellite-vs-plain-Xwayland decision recorded with `WAYLAND_DEBUG` evidence | Planned |
| **AC-03** | No `Xwayland` process at idle; Hyprland start-up time matches the TASK-026 baseline | Planned |
| **AC-04** | Launching an X11 client spawns XWayland on demand and its window appears in the desktop (screenshot) | Planned |
| **AC-05** | KiCad and a Swing app accept keyboard/pointer input and render correctly at the active render scale | Planned |
| **AC-06** | Wayland-native apps are unaffected; `DISPLAY=:0` changes no toolkit backend selection | Planned |
| **AC-07** | Missing satellite package fails soft: session still boots, Wayland apps unaffected | Planned |
| **AC-08** | Unit tests + `./gradlew assembleDebug` green; evidence in `docs/tasks/TASK-032/evidence/`; commit pushed to `origin/main` | Planned |

## 5. Verification Plan

1. Spike: `WAYLAND_DEBUG=1 xwayland-satellite :0` under the bridge; capture required globals.
2. Idle: start a session, `pgrep -a Xwayland` → empty; measure boot time.
3. First client: `DISPLAY=:0 xeyes` / KiCad; screenshot; confirm XWayland PID appears.
4. Input: keyboard + pointer + stylus in the X11 app.
5. Native apps: kitty/foot/Electron-native still start with the Wayland backend.
6. Rollback: remove the autostart; confirm the session returns to today's behaviour.

## 6. Risks & Mitigations

- **Satellite requires Wayland globals the bridge does not implement** → time-boxed spike with the
  explicit bail-out to plain rootless Xwayland; no open-ended protocol work in this task.
- **XWayland start-up regression** → keep `xwayland.enabled=false` and only spawn on connect;
  measure idle and first-client latency.
- **PRoot `sun_path` limit** → keep the socket at `/tmp/.X11-unix/X<n>`; verify length.
- **Package availability on Arch ARM** → soft-fail provisioning; `xorg-xwayland` alone enables
  the fallback.
- **Input/scaling differences in X11 apps** → verify at the active render scale and record
  screenshots; document any per-app scaling override.
