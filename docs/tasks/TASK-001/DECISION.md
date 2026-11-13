# TASK-001 — Architectural Decision Records (ADR-001 to ADR-009)

**Status:** Proposed Architecture Baseline (Phase 0)  
**Date:** 2026-11-13  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Context:** Turning an unrooted Xiaomi Pad 8 (Snapdragon 8s Gen 4, Adreno 825, HyperOS 2.0) into an Arch Linux ARM development machine with a native Wayland desktop.

---

## ADR-001: Decoupled, Base-Agnostic Modular Seams

### Context
Traditional Android chroot/PRoot solutions tightly couple UI buttons with shell scripts and X11/VNC servers. This makes maintaining, replacing, or modernizing components (e.g. moving from PRoot to native containers or microdroid VMs, or swapping compositors) nearly impossible without complete rewrites.

### Decision
Divide the codebase strictly into four modular boundaries:
1. `app-ui`: Android Compose presentation (Wizard, Configurator, HUD).
2. `session-core`: Session orchestrator, `StackProfile` engine, Foreground Service coordinator.
3. `proot-runtime`: PRoot execution harness, multi-rootfs isolation, bind mount engine, ptrace extensions.
4. `wayland-bridge`: Embedded C/Rust native Wayland protocol server, `ANativeWindow` presentation, pointer capture, and audio socket routing.

### Consequences & Rules
- Neither `app-ui` nor `session-core` may invoke raw PRoot CLI commands directly; they interact through structured interfaces provided by `proot-runtime`.
- The display layer (`wayland-bridge`) communicates with the compositor strictly over standard Wayland protocol sockets (`wayland-0`), allowing compositors (Hyprland, LabWC, Plasma) to be swapped without changing native Android display code.

---

## ADR-002: Profile-Driven Non-Destructive Runtime Injection

### Context
Users spend significant effort tuning their desktop configurations (`hyprland.conf`, shell rc files, Neovim configs). Automated installers that overwrite user dotfiles cause data loss and user frustration.

### Decision
Enforce the **Non-Destructive Invariant**:
All user settings are serialized into an immutable `StackProfile` JSON model. At runtime:
- System properties are passed via PRoot CLI arguments and environment variables.
- For Hyprland, a wrapper configuration is generated at `/tmp/android-session.conf` that executes `source = ~/.config/hypr/hyprland.conf` before appending hardware-specific workstation overrides.
- Hyprland is executed as `Hyprland -c /tmp/android-session.conf`.
- Dotfiles inside `/home/<user>/` are never modified or overwritten by the application.

### Consequences & Rules
- The user can edit `~/.config/hypr/hyprland.conf` freely using desktop tools without fear of the Android app overwriting their changes.
- Safe Mode (`LabWC + llvmpipe`) operates as an independent profile, guaranteeing instant recovery without touching user files.

---

## ADR-003: Direct Adreno 825 Acceleration via Mesa Turnip and `/dev/kgsl-3d0`

### Context
Desktop DRM/KMS drivers (`/dev/dri/renderD128`) are absent on stock Android kernels. Most Android Linux tools fall back to slow CPU software rendering (`llvmpipe`) or unstable GL-over-GLES wrappers (VirGL).

### Decision
Target direct user-space Vulkan acceleration on the Qualcomm Adreno 825 by:
1. Binding the kernel character device `/dev/kgsl-3d0` into the PRoot guest filesystem.
2. Managing Mesa Turnip drivers as external packages in Android app storage (`files/drivers/<driver-id>`).
3. Mounting the driver package to `/opt/turnip` and setting `MESA_LOADER_DRIVER_OVERRIDE=kgsl` and `VK_ICD_FILENAMES=/opt/turnip/share/vulkan/icd.d/freedreno_icd.aarch64.json`.

### Consequences & Rules
- Achieves native Vulkan 1.3 performance on unrooted devices.
- Mesa updates can be delivered or rolled back independently of pacman system updates.
- If `/dev/kgsl-3d0` access fails (e.g. strict SELinux rules on non-Qualcomm devices), the system falls back gracefully to Zink or `llvmpipe`.

---

## ADR-004: Native In-Process Wayland Server on `ANativeWindow`

### Context
Traditional solutions use Xwayland running on an internal X11 server (Termux-X11 or VNC). This introduces double buffering, high memory bandwidth overhead, frame tearing, and input latency.

### Decision
Embed a native C/Rust Wayland server directly inside the Android app process (referencing `Beauty114514/trierarch-app`). The server implements `wl_compositor`, `wl_subcompositor`, `wl_shm`, and `linux_dmabuf_v1`, rendering client buffers directly into the Android `ANativeWindow` associated with an in-app `SurfaceView`.

### Consequences & Rules
- Zero X11/VNC overhead for native Wayland clients.
- Frame presentation is synchronized directly with Android's Choreographer up to 144Hz.
- Legacy X11 apps are accommodated seamlessly via `xorg-xwayland` running on top of the native Wayland server.

---

## ADR-005: Relative Pointer Capture & Hardware Keyboard Remapping

### Context
1. Android mouse handling clamps the cursor to screen boundaries, breaking window resizing and first-person navigation.
2. Xiaomi HyperOS aggressively intercepts the physical `Super` (Windows) key for system search and home launcher actions.

### Decision
1. When the desktop surface is focused, invoke `View.requestPointerCapture()`, routing raw delta coordinates (`AXIS_RELATIVE_X`, `AXIS_RELATIVE_Y`) directly into Wayland `wl_pointer.motion`.
2. Implement dual-layer keyboard remapping:
   - Primary: Inject `kb_options = caps:super` into compositor configurations, turning the Caps Lock key into a functional Super key.
   - Secondary: Intercept raw hardware key events in the workstation `Activity` before HyperOS consumes them.

### Consequences & Rules
- Desktop mouse interaction feels identical to a native PC workstation.
- Caps Lock acts as the primary tiling window manager modifier (`Mod4`) without system interference.

---

## 6. ADR-006: Selective Storage Bridge & UFS Build Cache Redirection

### Context
1. Binding `/storage/emulated/0` naively exposes `/Android/data`, triggering Android 11+ Scoped Storage permission traps and kernel hangs.
2. Shared storage filesystems (sdcardfs/FUSE) are extremely slow for file operations, causing `cargo build` or `npm install` to crawl.

### Decision
1. Bind `/storage/emulated/0` to `/mnt/sdcard` while explicitly masking `/storage/emulated/0/Android/data` and `/storage/emulated/0/Android/obb`.
2. Automatically redirect heavy compiler and package manager caches to high-speed internal UFS storage:
   `CARGO_TARGET_DIR`, `UV_PROJECT_ENVIRONMENT`, `PNPM_STORE_DIR`, `GOCACHE` -> `/home/<user>/.cache/`.
3. Provide a `--symlink2file` ptrace extension to emulate symbolic links on shared storage.

### Consequences & Rules
- Prevents Android Scoped Storage kernel deadlocks.
- High-concurrency compilation operates at full UFS 4.0 flash speeds.

---

## 7. ADR-007: Transparent Foreign Binary Emulation Hook (`-q`)

### Context
Certain developer utilities and proprietary packages are only distributed as `x86_64` binaries. Running a full x86_64 rootfs incurs heavy emulation overhead on native ARM developer tools.

### Decision
Maintain a single native `aarch64` Arch Linux ARM rootfs. Configure PRoot with an optional `-q /opt/fex/bin/FEXInterpreter` (or `box64`) hook:
- When an `x86_64` binary is executed, PRoot redirects it to the translation engine with host Vulkan/GL thunking.
- When an `aarch64` binary is executed, it runs natively on Cortex-X4 / A720 cores with 100% native performance.

### Consequences & Rules
- Users can run `x86_64` developer tools without slowing down native compilation or compositor rendering.

---

## 8. ADR-008: Dual-Tier Lifecycle & Shizuku Companion (`UID 2000`)

### Context
1. Android Doze mode freezes the CPU when the tablet screen turns off.
2. Android 12+ Phantom Process Killer terminates child process trees exceeding 32 processes (fatal for Cargo/Clang builds).
3. Xiaomi HyperOS PowerKeeper aggressively terminates background processes.

### Decision
Implement a dual-tier lifecycle management system:
- **Tier 1 (Standard Android Sandbox):** Foreground Service with sticky notification and `PARTIAL_WAKE_LOCK`.
- **Tier 2 (Shizuku Companion `UID 2000`):** When Shizuku is running, execute:
  `device_config put activity_manager max_phantom_processes 2147483647`
  and whitelist the app from Xiaomi PowerKeeper.
- Stream real-time Adreno KGSL GPU clock and thermal telemetry to an overlay HUD.

### Consequences & Rules
- Compilation jobs with 32+ threads complete without random OS `SIGKILL` termination.
- If Shizuku is absent, the app operates gracefully in Tier 1 with clear user advisories.

---

## 9. ADR-009: Toolchain & Build Stack Baseline

### Context
A stable, modern toolchain baseline is required to build the Kotlin/Compose frontend, the C/Rust Wayland server, and PRoot native binaries.

### Decision
Adopt the following pinned toolchain baseline:
- **Host OS:** Linux (Ubuntu/Debian/Arch)
- **Android SDK:** Platform API 35 (Android 15), Min SDK 29 (Android 10), Target SDK 35
- **Build Tools:** Android SDK Build-Tools 35.0.0 / 36.0.0
- **Android NDK:** r27c (`27.3.13750724`)
- **JDK:** OpenJDK 17 (`/home/seal/toolchain/jdk-17`)
- **Gradle:** 8.11.1
- **Android Gradle Plugin (AGP):** 8.7.2
- **Kotlin:** 2.1.0 with Jetpack Compose (BOM 2024.11.00)
- **Native C/C++:** C17, C++20 via CMake 3.22.1+ and Ninja
- **Rust:** Rust 1.98.1 with `cargo-ndk` targeting `aarch64-linux-android`
