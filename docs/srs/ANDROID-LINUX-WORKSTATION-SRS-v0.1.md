# Software Requirements Specification (SRS)

## Android Linux Workstation (ALW)
### Arch Linux ARM (`aarch64`) with Native Wayland on Android Tablets

**Version:** 0.1 — Initial Architecture Baseline  
**Project Status:** Active Architecture & Specification  
**Primary Target Device:** Xiaomi Pad 8 (Qualcomm Snapdragon 8s Gen 4, Adreno 825 GPU, Xiaomi HyperOS / Android 15, API 35)  
**Execution Environment:** Unrooted Android Userspace with optional Shizuku (`UID 2000`) companion  
**Base Distribution:** Arch Linux ARM (`aarch64`)  
**Desktop Environment:** Native Wayland (Hyprland / LabWC / KDE Plasma)  
**Graphics Acceleration:** Direct Adreno Turnip / Freedreno Vulkan via `/dev/kgsl-3d0`  

---

## 1. Project Overview & Scope

### 1.1 Vision
To deliver a desktop-grade, low-latency, zero-terminal Linux workstation experience on unrooted modern Android hardware. The system transforms an Android tablet into a primary development machine running developer tools, language toolchains (Rust, Node/TypeScript, Python, Go, C/C++), and modern GUI IDEs (including VS Code, Antigravity, and JetBrains) within a fully accelerated native Wayland desktop.

### 1.2 Core Architectural Principles
1. **Base-Agnostic & Modular Seams:** The application core does not hard-code PRoot, Wayland, or Turnip. Each subsystem communicates across strict, decoupled interfaces (`app-ui`, `session-core`, `proot-runtime`, `wayland-bridge`).
2. **Zero-Terminal One-Tap Onboarding:** New users can initialize and boot directly into a working desktop within three taps, eliminating the manual shell setup typical of traditional chroot/PRoot environments.
3. **Non-Destructive Runtime Injection:** User configurations, compositor overrides, driver libraries, and environment variables are injected at runtime via the `StackProfile` JSON model. The system never overwrites user dotfiles (such as `~/.config/hypr/hyprland.conf`).
4. **Hardware-Native Performance:** Direct access to the Adreno 825 GPU via the Linux kernel's KGSL device node (`/dev/kgsl-3d0`) with Mesa Turnip user-space drivers, achieving native Vulkan performance.
5. **Robust System & Process Lifecycle:** Proactive management of Android's aggressive background process killers and phantom process restrictions, utilizing Android Foreground Services and an optional Shizuku companion.

### 1.3 Target Hardware Specification
- **SoC:** Qualcomm Snapdragon 8s Gen 4 (SM8735, TSMC 4nm)
- **CPU:** 1x Cortex-X4 @ 3.0 GHz, 4x Cortex-A720 @ 2.8 GHz, 3x Cortex-A520 @ 2.0 GHz (`aarch64`)
- **GPU:** Qualcomm Adreno 825 (Vulkan 1.3, OpenGL ES 3.2)
- **Display:** 11.2" / 12.4" LCD/OLED, up to 144Hz refresh rate, 3.2K resolution (e.g. 3200 x 2136)
- **OS:** Xiaomi HyperOS 2.0 (based on Android 15, Linux Kernel 6.6+ with KGSL driver)
- **Storage:** UFS 4.0 internal flash storage

---

## 2. Detailed Functional Requirements

### 2.1 One-Tap Onboarding & Auto-Provisioning (ONBOARD)

- **REQ-ONBOARD-001 (Wizard UI):** The application shall provide a zero-terminal First-Launch Wizard presenting exactly three streamlined options:
  1. Distro selection (default: Arch Linux ARM `aarch64`).
  2. Default Compositor selection (Hyprland default, LabWC fallback, KDE Plasma optional).
  3. GPU Driver preset (Mesa Turnip Adreno 825 KGSL default, Zink fallback, `llvmpipe` Safe Mode).
- **REQ-ONBOARD-002 (Single-Tap Automated Provisioning):** Upon tapping "Install & Start", the system shall download or extract the base Arch Linux ARM rootfs tarball into internal app storage (`files/rootfs/archlinux-default`) with real-time graphical progress reporting.
- **REQ-ONBOARD-003 (Pacman & Mirror Configuration):** The provisioning engine shall automatically initialize pacman keyrings (`pacman-key --init`, `pacman-key --populate archlinuxarm`) and configure optimized regional mirrors.
- **REQ-ONBOARD-004 (Core Packages Installation):** The provisioning script shall pre-install:
  - `xorg-xwayland` (for seamless legacy X11 app compatibility)
  - `mesa-utils` & `vulkan-tools` (for GPU diagnostics: `vkcube`, `glxgears`)
  - `pipewire`, `pipewire-pulse`, `wireplumber` (for low-latency audio)
  - `yay` / `paru` AUR helpers pre-configured with `--mflags "-A"` to allow `x86_64`-tagged PKGBUILDs to build natively on `aarch64`.
- **REQ-ONBOARD-005 (Global Electron/Chromium Environment):** The engine shall inject global system environment variables:
  - `ELECTRON_OZONE_PLATFORM_HINT=wayland`
  - `--no-sandbox` flags for desktop wrappers of Electron/Chromium applications (such as Antigravity and VS Code) to bypass PRoot seccomp restrictions.

---

### 2.2 Profile-Driven Stack Configurator (CONF)

- **REQ-CONF-001 (JSON Serialization):** All runtime configurations shall be modeled as an immutable, schema-validated `StackProfile` JSON object stored in Android internal app storage.
- **REQ-CONF-002 (Non-Destructive Injection):** The system shall strictly prohibit overwriting, truncating, or modifying user dotfiles inside `/home/<user>/`. All customizations must be passed via:
  - PRoot CLI arguments and `--bind` parameters.
  - Environment variables set during process exec.
  - Temporary session override configuration files generated in `/tmp/`.
- **REQ-CONF-003 (Compositor Wrapper Scripting):** For Hyprland, the session engine shall generate a temporary runtime configuration at `/tmp/android-session.conf` that executes `source = ~/.config/hypr/hyprland.conf` first, followed by app-controlled display, input, and backend overrides. Hyprland shall be launched as `Hyprland -c /tmp/android-session.conf`.
- **REQ-CONF-004 (Live Compositor IPC):** The application shall support dynamic runtime changes (resolution, scaling, key remapping) without restarting the desktop by issuing live IPC commands via `hyprctl keyword <key> <value>` over the Hyprland UNIX domain socket.
- **REQ-CONF-005 (Safe Mode Preset):** The configurator shall provide a one-tap "Safe Mode" option that enforces `LabWC` (Wayland compositor) combined with `MESA_LOADER_DRIVER_OVERRIDE=llvmpipe` (CPU software rasterizer), bypassing GPU and compositor crashing bugs.

---

### 2.3 PRoot Distro Manager & Runtime (PROOT)

- **REQ-PROOT-001 (Multi-Rootfs Lifecycle):** The runtime shall manage multiple isolated rootfs installations stored under `files/rootfs/<id>/`, allowing side-by-side installations, deletion, and active-profile binding.
- **REQ-PROOT-002 (Import & Export):** The runtime shall support importing custom rootfs archives (`.tar.xz`, `.tar.gz`, OCI container tarballs) and exporting rootfs snapshots to external storage.
- **REQ-PROOT-003 (Session Auto-Discovery):** The engine shall inspect `/usr/share/wayland-sessions/` within the guest rootfs to dynamically populate available desktop environments and compositors in the UI.
- **REQ-PROOT-004 (Ptrace & Syscall Emulation):** PRoot shall run with optimized ptrace handling, emulating fake root (`UID 0`) while managing unprivileged execution within Android's sandbox.
- **REQ-PROOT-005 (Soft-Symlink Emulation):** The runtime shall support an optional `--symlink2file` ptrace extension to transparently emulate symbolic links on shared Android storage filesystems (FAT32/exFAT/sdcardfs) that do not support POSIX symlinks.

---

### 2.4 GPU Driver Library & Turnip Acceleration (GPU)

- **REQ-GPU-001 (External Driver Storage):** Mesa Turnip driver packages shall be stored externally in Android app storage (`files/drivers/<driver-id>/`) outside the rootfs, enabling instant driver updates and rollbacks without modifying guest system packages.
- **REQ-GPU-002 (Direct KGSL Access):** The runtime shall bind the Linux kernel graphics device node `/dev/kgsl-3d0` into the PRoot guest filesystem with appropriate read/write permissions for the guest user.
- **REQ-GPU-003 (Dynamic Environment Export):** When launching a session with Turnip enabled, the engine shall dynamically inject:
  - `VK_ICD_FILENAMES=/opt/turnip/share/vulkan/icd.d/freedreno_icd.aarch64.json`
  - `LIBGL_DRIVERS_PATH=/opt/turnip/lib/dri`
  - `LD_LIBRARY_PATH=/opt/turnip/lib:$LD_LIBRARY_PATH`
  - `MESA_LOADER_DRIVER_OVERRIDE=kgsl`
- **REQ-GPU-004 (Driver Presets):** The engine shall support four distinct GPU modes:
  1. `turnip-kgsl` (Mesa Turnip direct hardware acceleration, target: Adreno 825)
  2. `turnip-zink` (OpenGL over Turnip Vulkan via Zink)
  3. `virgl` / `venus` (virtualized guest GPU when running in hypervisors)
  4. `llvmpipe` (CPU software rasterizer fallback)

---

### 2.5 Native Wayland Bridge & Embedded Surface (WAYLAND)

- **REQ-WAYLAND-001 (Embedded Android Surface):** The display bridge shall embed a native C/Rust Wayland server directly inside an Android `SurfaceView`, passing `ANativeWindow` references to the native compositor without X11 server overhead.
- **REQ-WAYLAND-002 (Wayland Protocols):** The bridge shall implement core Wayland protocols (`wl_compositor`, `wl_subcompositor`, `wl_shm`, `xdg_shell`, `linux_dmabuf_v1`) to interface cleanly with Hyprland and LabWC.
- **REQ-WAYLAND-003 (Pointer Capture & Relative Mouse):** The Wayland surface shall invoke `View.requestPointerCapture()` when focused, ensuring seamless 1:1 hardware relative mouse movement, scroll wheel input, and zero cursor jumping.
- **REQ-WAYLAND-004 (Keyboard Remapping):** To prevent Xiaomi HyperOS from intercepting the physical `Super` (Windows) key for Android system navigation, the input bridge shall automatically inject `kb_options = caps:super` (or custom scancode translation) into the session configuration.
- **REQ-WAYLAND-005 (PipeWire Audio Socket):** The bridge shall establish a local UNIX domain socket bridging guest PipeWire/PulseAudio streams directly to Android's `AudioTrack` or Oboe audio output with sub-30ms latency.

---

### 2.6 Storage & SDCard Bridge (STORAGE)

- **REQ-STORAGE-001 (Selective SDCard Bind Mount):** The runtime shall bind `/storage/emulated/0` to `/mnt/sdcard` inside the rootfs.
- **REQ-STORAGE-002 (Android/data Exclusion):** The runtime shall explicitly exclude or mask `/storage/emulated/0/Android/data` and `/storage/emulated/0/Android/obb` from PRoot bind mounts to avoid Android 11+ Scoped Storage permission traps and kernel I/O hangs.
- **REQ-STORAGE-003 (High-Speed UFS Cache Redirection):** To prevent severe performance degradation caused by compiling code on FUSE/FAT shared storage, the session engine shall redirect all heavy build caches to internal UFS storage:
  - `export CARGO_TARGET_DIR=/home/<user>/.cache/cargo-target`
  - `export UV_PROJECT_ENVIRONMENT=/home/<user>/.cache/uv-env`
  - `export PNPM_STORE_DIR=/home/<user>/.cache/pnpm-store`
  - `export GOCACHE=/home/<user>/.cache/go-build`

---

### 2.7 Transparent x86_64 Emulation Layer (X86)

- **REQ-X86-001 (Transparent PRoot Hook):** The runtime shall provide an optional `-q` parameter hook for PRoot pointing to `FEXInterpreter` or `box64`.
- **REQ-X86-002 (Zero Overhead on Native aarch64):** Foreign binary translation shall be invoked strictly when executing `x86_64` ELFs. Native `aarch64` binaries shall run directly on the host ARM cores with zero emulation overhead.
- **REQ-X86-003 (Vulkan & GL Thunking):** When using `FEXInterpreter`, the system shall mount host Vulkan and OpenGL thunk libraries into the FEX root to allow x86_64 applications direct access to the Adreno Turnip driver.

---

### 2.8 Process Lifecycle & Shizuku Companion (LIFECYCLE)

- **REQ-LIFECYCLE-001 (Android Foreground Service):** The core workstation process shall run bound to an active Android Foreground Service displaying a persistent notification and acquiring a `PARTIAL_WAKE_LOCK` to prevent CPU sleep during long compilation jobs.
- **REQ-LIFECYCLE-002 (Phantom Process Warning):** The application shall detect Android 12+ child process restrictions ("Phantom Process Killer") and warn the user if `max_phantom_processes` is limiting child tasks.
- **REQ-LIFECYCLE-003 (Shizuku Companion Integration):** When Shizuku (`UID 2000`) is active, the app shall provide one-tap execution of:
  `device_config put activity_manager max_phantom_processes 2147483647`
  and automatically whitelist the app from Xiaomi HyperOS PowerKeeper battery optimizations.
- **REQ-LIFECYCLE-004 (GPU Telemetry HUD):** When Shizuku or sysfs access is available, the app shall stream real-time Adreno 825 GPU clock frequency, temperature, and KGSL memory usage into an optional Compose overlay HUD.

---

## 3. Phased Implementation Roadmap

- **Phase 0: Architecture, Docs, Planning Baseline & Decision Records (ADRs)**
  - Mirror Dev Framework v0.2 structure.
  - Finalize SRS, Architecture Specifications, `StackProfile` schema, and ADRs.
  - Establish toolchain baseline and module boundaries.
- **Phase 1: Core Runtime & PRoot Distro Engine**
  - Implement `session-core` and `proot-runtime`.
  - Multi-rootfs directory management (`files/rootfs/<id>`).
  - Automated provisioning script for Arch Linux ARM, `pacman`, `yay`/`paru`.
  - Storage bridge and cache redirection.
- **Phase 2: Native Wayland Bridge & Turnip KGSL GPU Acceleration**
  - Implement `wayland-bridge` native C/Rust layer.
  - Embedded Wayland surface and protocol handlers.
  - Direct KGSL `/dev/kgsl-3d0` binding and external Mesa Turnip driver manager.
  - Pointer capture, keyboard mapping (`caps:super`), and PipeWire audio forwarding.
  - Safe Mode (`LabWC + llvmpipe`) verification.
- **Phase 3: Zero-Terminal Onboarding UI & Stack Configurator**
  - Implement `app-ui` with Jetpack Compose.
  - First-Launch Wizard, Compositor Switcher, and Stack Configurator.
  - Shizuku (`UID 2000`) companion integration and GPU telemetry HUD.
- **Phase 4: Hardware Integration, Benchmarking & Production Hardening**
  - Testing and optimization on Xiaomi Pad 8 (Snapdragon 8s Gen 4 / Adreno 825).
  - High-load compilation benchmarks (Rust/Cargo, Chromium/Electron apps).
  - Thermal throttling, battery consumption, and phantom process stress testing.
  - Future preparation for Phase 5 (transparent split overlay keyboard).
