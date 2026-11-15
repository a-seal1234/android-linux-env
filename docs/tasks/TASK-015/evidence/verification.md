# TASK-015: Verification Evidence

## 1. Scope & Acceptance Criteria
- **Task**: Dynamic OCI Registry Catalog, Tag Fetcher & Multi-Layer Rootfs Puller.
- **Architectural Modules**: `core:proot`, `app`.
- **Key Capabilities Delivered**:
  1. **OCI Distribution Spec v2 & Docker Registry Client (`OciRegistryClient`)**:
     - Anonymous OAuth2 Bearer token authentication via `Www-Authenticate` challenge flow.
     - Multi-arch image index / manifest list parsing with strict `linux/arm64` (`aarch64`) platform selection.
     - Curated distro catalog (`archlinux`, `ubuntu`, `debian`, `fedora`, `kalilinux/kali-rolling`, `alpine`, `opensuse/tumbleweed`) with live tag queries.
  2. **Multi-Layer Stream Extractor with Zstd & OCI Whiteouts (`OciLayerExtractor`)**:
     - Sequential layer extraction preserving base-to-top ordering.
     - Stream decompression supporting Gzip (`GzipCompressorInputStream`) and Zstandard (`com.github.luben:zstd-jni`).
     - Standard OCI whiteout resolution (`.wh.<filename>` deletes lower-layer file).
     - Opaque directory whiteout resolution (`.wh..wh..opq` purges lower-layer directory contents).
     - Anti-ZipSlip path traversal verification and PRoot non-root symlink/hardlink emulation.
  3. **Multi-Distro Zero-Terminal Bootstrapper (`MultiDistroBootstrapper`)**:
     - Automatic guest distribution detection via `/etc/os-release` or `/usr/lib/os-release`.
     - Distro-tailored package installation and user creation:
       - `ARCH`: `pacman`
       - `DEBIAN` / `UBUNTU` / `KALI`: `apt-get`
       - `FEDORA`: `dnf`
       - `ALPINE`: `apk`
       - `OPENSUSE`: `zypper`
     - Automatic injection of passwordless sudoers (`/etc/sudoers.d/010_alw`), DNS (`/etc/resolv.conf`), hostnames (`/etc/hosts`), and desktop session environment (`PULSE_SERVER`, `WAYLAND_DISPLAY`, `XDG_RUNTIME_DIR`).
  4. **UI Integration**:
     - Wizard Step 1: Added OCI Registry Catalog browser with curated distro chips, tag selector chips, live tag querying, and custom image input (`image:tag`).
     - Dashboard: Added quick OCI catalog presets in the "New Workspace" creation dialog.

---

## 2. Unit Test Results

### `core:proot` Tests
All 66 unit tests passed:
- `OciRegistryClientTest`:
  - `testNormalizeRepository`: Validates `ubuntu` -> `library/ubuntu`, `alpine` -> `library/alpine`, etc.
  - `testArm64PlatformDetection`: Validates `linux/arm64` and `linux/aarch64` matching while rejecting x86/Windows.
  - `testMultiArchManifestListParsing`: Validates extraction of arm64 digest from multi-arch manifest list.
  - `testDistroCatalogIntegrity`: Validates curated distro entries.
- `OciLayerExtractorTest`:
  - `testStandardAndOpaqueWhiteoutResolution`: Validates deletion of `.wh.deleted.conf` and clearing of opaque directory `var/log/.wh..wh..opq`.
  - `testAntiZipSlipGuard`: Validates rejection of `../../malicious.sh` entry with `SecurityException`.
- `MultiDistroBootstrapperTest`:
  - `testDetectUbuntu`: Validates Debian family detection and `apt-get` bootstrap generation.
  - `testDetectArchLinux`: Validates Arch family detection and `pacman` bootstrap generation.
  - `testDetectFedora`: Validates Fedora family detection and `dnf` bootstrap generation.
  - `testDetectAlpine`: Validates Alpine family detection and `apk` bootstrap generation.

### `app` Tests
- `WizardViewModelTest`:
  - `testOciSelectionFlow`: Validates selecting OCI source, changing distros, updating tags, and custom image setting.
  - All existing wizard and dashboard tests pass.

Command Output:
```
BUILD SUCCESSFUL in 6s
159 actionable tasks: 10 executed, 149 up-to-date
```

---

## 3. Build Verification
- `./gradlew assembleDebug`: Clean build completed (exit code 0).
- Debug APK packaging verified with `zstd-jni` arm64-v8a native bindings.
