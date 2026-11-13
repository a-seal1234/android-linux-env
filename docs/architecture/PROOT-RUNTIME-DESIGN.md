# PRoot Runtime Engine Design Specification

**Status:** Approved Specification (Phase 0)  
**Document:** `docs/architecture/PROOT-RUNTIME-DESIGN.md`  
**Core Technologies:** PRoot v5.4.0+, Linux Ptrace, Seccomp Filter, Arch Linux ARM  

---

## 1. Overview & Responsibilities

The `proot-runtime` module provides an isolated, unrooted userspace container for running standard Linux distributions (`aarch64`) inside Android's application sandbox. By utilizing Linux `ptrace(2)` and seccomp filters, PRoot intercepts syscalls requiring root privileges (such as `chroot`, `mount`, `mknod`, `setuid`) and translates paths and user IDs transparently.

```
+--------------------------------------------------------------------------+
|                     proot-runtime (Module Core)                          |
|                                                                          |
|   +-------------------+  +-------------------+  +--------------------+   |
|   | Multi-Rootfs Mgr  |  | Bind Mount Engine |  | Provisioning Engine|   |
|   | files/rootfs/<id> |  | Selective Storage |  | pacman, yay, flags |   |
|   +---------+---------+  +---------+---------+  +---------+----------+   |
+-------------|----------------------|----------------------|--------------+
              |                      |                      |
              v                      v                      v
+--------------------------------------------------------------------------+
|                       PRoot Process Execution                            |
|   proot -0 -r <rootfs_dir> -b /dev -b /proc -b /sys -b /dev/kgsl-3d0 ...  |
|   -b /tmp/runtime-alw:$XDG_RUNTIME_DIR -b /mnt/sdcard                    |
|   [-q /opt/fex/bin/FEXInterpreter] /usr/bin/env -i <env_vars> <command>   |
+--------------------------------------------------------------------------+
```

---

## 2. Directory Layout & Multi-Rootfs Lifecycle

All Linux filesystems are maintained within the Android app's private internal storage (`/data/user/0/<package-id>/files/`), benefiting from high-speed UFS 4.0 flash performance and standard Android sandbox protection.

```
<app_internal_data>/files/
├── rootfs/
│   ├── archlinux-default/         # Default Arch Linux ARM installation
│   │   ├── bin -> usr/bin
│   │   ├── etc/
│   │   ├── home/
│   │   │   └── alw/               # User home directory (dotfiles preserved)
│   │   │       └── .cache/        # High-speed UFS build cache directory
│   │   ├── usr/
│   │   │   └── share/
│   │   │       └── wayland-sessions/ # Auto-discovered sessions (.desktop)
│   │   └── var/
│   └── debian-experimental/       # Alternative isolated rootfs
├── drivers/
│   ├── turnip-mesa-24.3/          # External Mesa Turnip driver package
│   │   ├── lib/
│   │   │   └── dri/
│   │   └── share/vulkan/icd.d/
│   └── turnip-mesa-git/
└── runtime/
    └── run-0/                     # Host XDG_RUNTIME_DIR (wayland-0, pulse socket)
```

### Multi-Rootfs Operations
1. **Creation / Provisioning:** Rootfs archives are unpacked with ownership and permission normalization.
2. **Snapshot Cloning:** Duplicates existing rootfs directories using fast copy-on-write or parallel directory cloning.
3. **Session Discovery:** Scans `<rootfs>/usr/share/wayland-sessions/*.desktop` to extract `Exec` and `Name` fields, automatically surfacing available desktops in the UI.

---

## 3. Automated Rootfs Provisioning Script

Upon initial onboarding tap, `proot-runtime` executes an automated provisioning sequence:

```bash
#!/bin/bash
set -eo pipefail

echo "==> Step 1: Initializing pacman keyrings..."
pacman-key --init
pacman-key --populate archlinuxarm

echo "==> Step 2: Synchronizing repositories..."
pacman -Syu --noconfirm

echo "==> Step 3: Installing Core Workstation Packages..."
pacman -S --noconfirm --needed \
    xorg-xwayland \
    mesa-utils \
    vulkan-tools \
    pipewire \
    pipewire-pulse \
    wireplumber \
    hyprland \
    labwc \
    kitty \
    neovim \
    git \
    sudo \
    base-devel

echo "==> Step 4: Installing AUR Helper (yay / paru) with aarch64 native flag..."
# yay/paru wrapper configured with --mflags "-A" so x86_64-tagged PKGBUILDs compile natively
cat <<'EOF' > /usr/local/bin/makepkg-wrapper
#!/bin/bash
exec /usr/bin/makepkg --mflags "-A" "$@"
EOF
chmod +x /usr/local/bin/makepkg-wrapper

echo "==> Step 5: Configuring Global Wayland & Sandbox Flags..."
cat <<'EOF' >> /etc/environment
ELECTRON_OZONE_PLATFORM_HINT=wayland
MOZ_ENABLE_WAYLAND=1
QT_QPA_PLATFORM=wayland
XDG_SESSION_TYPE=wayland
EOF

# Electron desktop overrides for apps like Antigravity & VS Code
cat <<'EOF' > /etc/chromium-flags.conf
--ozone-platform-hint=wayland
--no-sandbox
--disable-gpu-sandbox
EOF
```

---

## 4. Bind Mount Architecture & Cache Isolation

### 4.1 Required Bind Mount Table

| Host Source Path | Guest Target Path | Mode | Purpose |
|---|---|---|---|
| `/dev` | `/dev` | Bind | Device nodes (`/dev/null`, `/dev/urandom`, `/dev/zero`) |
| `/dev/kgsl-3d0` | `/dev/kgsl-3d0` | Bind (RW) | Direct Adreno 825 hardware GPU access |
| `/proc` | `/proc` | Bind | Kernel process information |
| `/sys` | `/sys` | Bind | System and thermal information |
| `/data/.../runtime/run-0` | `/tmp/runtime-alw` | Bind (RW) | Wayland socket (`wayland-0`), PipeWire socket |
| `/data/.../drivers/<id>` | `/opt/turnip` | Bind (RO) | External Mesa Turnip driver package |
| `/storage/emulated/0` | `/mnt/sdcard` | Bind (RW) | User media and documents (Selective) |

### 4.2 Storage Exclusion Policy
Android Scoped Storage prohibits direct access to `/storage/emulated/0/Android/data` and `/storage/emulated/0/Android/obb`. Accessing these directories in PRoot triggers kernel EACCES or FUSE driver hangs.
- The bind mount engine filters out these paths:
  `--bind /storage/emulated/0:/mnt/sdcard`
  followed by masking binds:
  `--bind /data/.../empty_dir:/mnt/sdcard/Android/data`
  `--bind /data/.../empty_dir:/mnt/sdcard/Android/obb`

### 4.3 High-Speed UFS Build Cache Redirection
To maximize compilation speeds (preventing slow FUSE/sdcardfs operations), the environment generator exports:
```bash
export CARGO_TARGET_DIR=/home/alw/.cache/cargo-target
export UV_PROJECT_ENVIRONMENT=/home/alw/.cache/uv-env
export PNPM_STORE_DIR=/home/alw/.cache/pnpm-store
export GOCACHE=/home/alw/.cache/go-build
```

---

## 5. Ptrace Extensions & Translation Layer

### 5.1 `--symlink2file` Extension
Android shared storage (`/storage/emulated/0`) lacks native POSIX symlink support. The custom PRoot `--symlink2file` extension:
- Detects `symlink(target, linkpath)` calls targeting `/mnt/sdcard`.
- Writes a special header file (`!#alw-symlink\n<target>`) rather than failing with `EPERM`.
- On `readlink` or `open`, resolves the linkpath transparently.

### 5.2 Transparent x86_64 Emulation (`-q`)
When enabled in `StackProfile`:
- PRoot registers the `-q /opt/fex/bin/FEXInterpreter` handler.
- When an ELF binary with machine type `EM_X86_64` (0x3E) is executed, PRoot invokes FEXInterpreter.
- When a native `EM_AARCH64` (0xB7) ELF binary is executed, PRoot bypasses the emulator, executing directly on Cortex-X4 / A720 cores with 100% native performance.
