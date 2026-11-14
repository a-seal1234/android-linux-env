# TASK-021: Verification Evidence

## 1. Scope & Objective
- **Objective**: Implement the full Wayland wire protocol server state machine in native C++20 and connect client buffer commits directly to `ANativeWindow` frame presentation (Phase B).
- **Module Involved**: `bridge:wayland`.

## 2. Implementations
1. **Binary Protocol Serialization & Deserialization (`wayland_bridge.cpp`)**:
   - `WaylandMessageWriter`: Encodes 8-byte header (`[uint32 obj_id, uint16 size, uint16 opcode]`) with automatic size updates, 4-byte string padding, and fixed-point data types.
   - `WaylandMessageReader`: Bounds-checked parsing of uint32, int32, string, and fixed types.

2. **Core Wayland Protocol Handshake & Dispatching**:
   - `wl_display`:
     - Opcode 0 (`sync`): Emits `wl_callback.done(serial)`.
     - Opcode 1 (`get_registry`): Dispatches all global announcements (`wl_registry.global`) for `wl_compositor`, `wl_subcompositor`, `wl_shm`, `xdg_wm_base`, `zwp_tablet_manager_v2`, `wl_seat`, and `wl_output`.
   - `wl_registry`:
     - Opcode 0 (`bind`): Instantiates client-bound global interfaces.
   - `wl_shm`:
     - Dispatches `wl_shm.format` for `ARGB8888` (0) and `XRGB8888` (1).
     - Receives memory-mapped file descriptors passed over UNIX socket using `recvmsg` with `SCM_RIGHTS`.
     - Maps client shared memory pool via `mmap(nullptr, size, PROT_READ, MAP_SHARED, fd, 0)`.
   - `wl_shm_pool`:
     - Handles `create_buffer` with offset, width, height, stride, and format.
   - `wl_output`:
     - Emits `geometry`, `mode` (3200x2136 @ 144Hz with `current | preferred` flags), `scale` (1.75 HiDPI factor), and `done`.
   - `wl_seat`:
     - Emits `capabilities` (pointer | keyboard | touch) and `name("seat0")`.

3. **Surface Management & Direct Frame Blitter**:
   - Tracks `wl_compositor.create_surface`, `wl_surface.attach`, `wl_surface.frame`, and `wl_surface.commit`.
   - On `wl_surface.commit`:
     - Resolves attached buffer and its backing memory-mapped `wl_shm_pool`.
     - Acquires `ANativeWindow_Buffer` via `ANativeWindow_lock`.
     - Performs row-by-row memory copy (`std::memcpy`) from client pixels to `ANativeWindow_Buffer.bits`.
     - Posts the frame to the Android hardware composer via `ANativeWindow_unlockAndPost`.
     - Emits `wl_callback.done(timestamp)` to client frame callbacks to drive the client's vsync presentation loop.

## 3. Test & Build Results
- `./gradlew :bridge:wayland:assembleDebug`: Clean compilation and linking of `libwayland_bridge.so`.
- `./gradlew test`: 100% tests passing across all modules.
- `./gradlew assembleDebug`: Complete APK assembled successfully.
