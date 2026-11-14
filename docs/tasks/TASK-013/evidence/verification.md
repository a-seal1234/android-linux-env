# TASK-013 Verification & Hardware Evidence Record

**Task ID:** TASK-013  
**Description:** Implement `app-ui` Stack Configurator, Shared Turnip Driver Store Manager & Live Compositor Switcher  
**Date:** 2026-11-14  
**Primary Hardware:** Xiaomi Pad 8 (`25097RP43G`, Snapdragon 8s Gen 4, Adreno 825, Android 16 / HyperOS)  
**Status:** PASSED (Verified via Automated Test Suite and Physical On-Device Execution)

---

## 1. Architectural Implementations

### 1.1 Official Arch Linux ARM Rootfs Stream Downloader (`RootfsDownloader.kt`)
- **Multi-Mirror Sequential Failover:** Resolves official Arch Linux ARM rootfs tarballs (`ArchLinuxARM-aarch64-latest.tar.gz`) across mirror endpoints:
  - `http://os.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz`
  - `http://mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz`
  - `http://fl.us.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz`
  - `http://dk.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz`
  - `http://de3.mirror.archlinuxarm.org/os/ArchLinuxARM-aarch64-latest.tar.gz`
- **Redirect Following:** Automatically follows HTTP 301, 302, 303, 307, and 308 redirects with Location header parsing.
- **Atomic Caching:** Streams directly to `${targetFile}.part`, enforcing a minimum valid file size threshold before atomically renaming to the permanent cached file in `files/downloads/`.
- **Progress Telemetry:** Emits structured `DownloadProgress` events with downloaded bytes, total bytes, percentage, download speed (MB/s), and active mirror origin.

### 1.2 Stack Configurator Screen & State Machine (`StackConfiguratorScreen.kt` & `StackConfiguratorViewModel.kt`)
- **Non-Destructive Invariant:** Mutates the versioned immutable `StackProfile` JSON model without overwriting guest dotfiles.
- **Configurable Sections:**
  1. *General Identity:* Profile name, ID, and bound rootfs workspace selector.
  2. *Wayland Compositor Engine:* Dynamic tiling (`HYPRLAND`) vs Stacking Safe Mode (`LABWC`).
  3. *GPU Acceleration:* `TURNIP_KGSL` (direct `/dev/kgsl-3d0` node), `ADRENO_PROPRIETARY_ZINK`, or `LLVMPIPE` CPU fallback.
  4. *Adreno 825 Conformance Fix:* Dedicated toggle for `TU_DEBUG=noconform` / `tuDebugNoConform = true` to bypass strict CTS checks on early A8xx Turnip drivers.
  5. *Display & HiDPI Scaling:* Quick presets (1.5x, 1.75x Xiaomi Pad 8 native, 2.0x) and continuous slider (1.0x - 2.5x), plus target refresh rates (60, 90, 120, 144 Hz).
  6. *Hardware Input:* Hardware CapsLock rebind to Super (`caps:super` / scancode 58 translation) and touchpad natural scrolling.
  7. *Storage Bridge:* Internal high-speed UFS build cache redirection (`~/.cache/` for cargo/uv/pnpm) and Scoped Storage `/mnt/sdcard` binding with `Android/data` isolation.
  8. *Custom Environment Variables:* Dynamic key-value pairs stored in `gpu.environmentOverrides`.
- **Validation Guard:** Calls `ProfileValidator.validate()` before persistence, ensuring identity safety, display bounds, and path integrity. Supports duplicate and delete actions.

### 1.3 Shared Turnip Driver Store Manager (`DriverStoreScreen.kt` & `DriverStoreViewModel.kt`)
- **Centralized Host Storage:** Manages `files/drivers/turnip/<version>/` directories with `manifest.json`.
- **Online Release Fetcher:** Queries curated Mesa 25.0+ Adreno 825 KGSL packages from GitHub releases with SHA256 cryptographic verification.
- **Active Driver Binding:** Sets active driver marker (`active-driver.txt`) and generates `/opt/drivers/turnip/active/freedreno_icd.aarch64.json` read-only mount target.
- **Local Driver Archive Import:** Supports `.tar.xz`, `.tar.gz`, and `.zip` imports with ZipSlip/TarSlip path traversal guards and ELF `glibc` `aarch64` ABI validation.

### 1.4 Live Compositor & Safe Mode Switcher (`DashboardScreen.kt`, `DashboardViewModel.kt`, `FloatingControlPill.kt`)
- **One-Tap Safe Mode:** Toggles active profile immediately to `LABWC` + `LLVMPIPE` for fail-safe desktop recovery.
- **Live Hyprland IPC:** Dispatches `hyprctl reload` via socket `/tmp/hypr/$HYPRLAND_INSTANCE_SIGNATURE/.socket.sock` to re-read configurations without terminating the Wayland session.
- **Default Dotfile Synthesis:** Non-destructively regenerates missing `~/.config/hypr/hyprland.conf` or `~/.config/labwc/rc.xml` via `ConfigSynthesizer`.

---

## 2. Test Execution & Build Verification

### 2.1 Unit Tests
```
$ ./gradlew test
> Task :core:session:test UP-TO-DATE
> Task :bridge:wayland:test UP-TO-DATE
> Task :core:proot:test UP-TO-DATE
> Task :app:test UP-TO-DATE

BUILD SUCCESSFUL in 1s
159 actionable tasks: 159 up-to-date
```

**New Unit Tests Added:**
- `core/proot/src/test/java/com/alw/workstation/core/proot/RootfsDownloaderTest.kt`:
  - `testCachedRootfsReturnedImmediately`: 100% immediate cache hit without network.
  - `testDownloadSucceedsFromFirstMirror`: Socket-level stream download, progress telemetry, and atomic `.part` rename.
  - `testMirrorFailoverWhenFirstMirrorFails`: 500 error failover to backup mirror.
  - `testRedirectFollowing`: HTTP 302 redirect following to target URL.
  - `testAllMirrorsFailThrowsIOException`: Exhaustion of all mirrors properly reports error history.
- `app/src/test/java/com/alw/workstation/ui/configurator/StackConfiguratorViewModelTest.kt`:
  - `testInitialStateAndLoadProfile`: New profile creation vs existing profile loading.
  - `testUpdateFields`: Full mutation coverage of all 8 configuration domains.
  - `testSaveProfileValidationFailureAndSuccess`: Validation error interception and clean save.
  - `testDuplicateProfile`: Clones profile with `-copy` suffix.
  - `testDeleteProfile`: Clean deletion and last-profile deletion guard.
- `app/src/test/java/com/alw/workstation/ui/driverstore/DriverStoreViewModelTest.kt`:
  - `testInitialStateAndOnlineReleases`: Release discovery for Adreno 825.
  - `testActivateDriverUpdatesState`: Active driver marker and state update.
  - `testDeleteDriver`: Deletion of stored driver version.

### 2.2 APK Assembly & Hardware Smoke Test
```
$ ./gradlew assembleDebug
BUILD SUCCESSFUL in 1s
-rw-r--r-- 1 seal seal 26M Nov 14 18:29 app/build/outputs/apk/debug/app-debug.apk

$ ./gradlew installDebug
Installing APK 'app-debug.apk' on '25097RP43G - 16' for :app:debug
Installed on 1 device.
BUILD SUCCESSFUL in 4s
```

### 2.3 Physical Device Verification Evidence
- [task013_smoke.png](task013_smoke.png): Workstation Dashboard showing live Compositor Switcher, Driver Store button, and Stack Profile cards.
- [task013_configurator.png](task013_configurator.png): Interactive `StackConfiguratorScreen` showing General Identity, Wayland Compositor selection, GPU Driver Mode, Adreno 825 conformance switch, HiDPI scaling, and CapsLock remap options.
- [task013_driverstore.png](task013_driverstore.png): Interactive `DriverStoreScreen` showing active hardware binding, curated Mesa 25.0+ Adreno 825 releases, and local archive import.
