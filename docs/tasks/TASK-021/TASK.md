# TASK-021 — Embedded Wayland Protocol Server & ANativeWindow Blitter

**Epic:** [EPIC-ALW-P2 — Phase 2](../../initiatives/android-linux-workstation/epics/phase-2-wayland-turnip-compositor/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Implementation / Core Engine Task  
**Board:** [Central task board](../../../TASKS.md) is the only workflow-status source.  
**Owner / harness:** Codex / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Risk:** Critical  
**Source:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md)  

---

## 1. Outcome

Implement the full Wayland wire protocol server state machine in `bridge:wayland` C++20 and connect client buffer commits directly to `ANativeWindow` frame presentation, enabling nested compositors (Hyprland, LabWC) and native Wayland clients to negotiate display globals, map shared memory buffers (`wl_shm`), and render interactively to the Xiaomi Pad 8 screen at 3200x2136 144Hz.

---

## 2. Source Requirements

- **REQ-WAYLAND-001:** Embedded Native Wayland Server on `ANativeWindow`
- **REQ-WAYLAND-002:** Core Wayland Protocols (`wl_shm`, `wl_compositor`, `xdg_shell`, `wl_output`, `wl_seat`)
- **REQ-CONF-003 / REQ-CONF-005:** Zero-crash nested compositor startup for Hyprland and LabWC.

---

## 3. Planned Scope & Acceptance Criteria

1. **Wayland Wire Protocol Dispatcher (`wayland_bridge.cpp`):**
   - Implement binary wire protocol parsing: `[uint32 id, uint16 size, uint16 opcode, args...]`.
   - Implement `wl_display`:
     - Opcode 0 (`sync`): Emit `wl_callback.done(serial)`.
     - Opcode 1 (`get_registry`): Register client registry object.
   - Implement `wl_registry`:
     - Announce globals: `wl_compositor`, `wl_subcompositor`, `wl_shm`, `xdg_wm_base`, `zwp_tablet_manager_v2`, `wl_seat`, `wl_output`.
     - Handle `bind`: instantiate client objects for compositor, shm, seat, output, etc.

2. **Shared Memory (`wl_shm`) & Buffer Mapping:**
   - Support `recvmsg` with `SCM_RIGHTS` ancillary data to receive memory-mapped file descriptors.
   - Implement `wl_shm_pool.create_buffer(id, offset, width, height, stride, format)`.
   - Send `wl_shm.format` events (`ARGB8888`, `XRGB8888`).

3. **Surface Management & Direct Frame Blitter:**
   - Implement `wl_compositor.create_surface`.
   - Implement `wl_surface.attach(buffer_id)` and `wl_surface.commit()`.
   - Implement `xdg_wm_base` and `xdg_surface` protocol responses.
   - On `wl_surface.commit()`, lock `ANativeWindow_Buffer` via `ANativeWindow_lock`, blit client buffer pixels, and post via `ANativeWindow_unlockAndPost`.
   - Emit `wl_callback.done` to client frame callbacks to drive client presentation loops.

4. **Testing & Hardware Verification:**
   - Unit tests covering protocol message parsing, registry announcement serialization, and buffer mapping.
   - AssembleDebug build verification.

---

## 4. Dependencies & Gates

- **Prerequisites:** TASK-007, TASK-010, TASK-020.
- **Status:** Done.
- **Evidence:** [Verification Evidence](evidence/verification.md)
