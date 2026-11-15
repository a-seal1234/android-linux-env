# TASK-015 — Dynamic OCI Registry Catalog, Tag Fetcher & Multi-Layer Rootfs Puller

**Epic:** [EPIC-ALW-P3 — Phase 3](../../initiatives/android-linux-workstation/epics/phase-3-onboarding-ui-integrations/EPIC.md)  
**Initiative:** [Android Linux Workstation](../../initiatives/android-linux-workstation/INITIATIVE.md)  
**Task type:** Implementation / Engineering Task  
**Board:** [Central task board](../../../TASKS.md) is the only workflow-status source.  
**Owner / harness:** Codex / local Linux environment  
**Planning workspace / branch / base:** `/media/DATA/project/android-linux-env` / `main`  
**Risk:** High Risk  
**Source:** [SRS v0.1](../../srs/ANDROID-LINUX-WORKSTATION-SRS-v0.1.md)  

---

## 1. Outcome

Implement an OCI Distribution Spec v2 & Docker Hub v2 image puller, multi-layer extractor, and multi-distro bootstrapper in `core:proot`, enabling dynamic discovery and installation of official Linux distributions (`ubuntu`, `debian`, `fedora`, `kalilinux/kali-rolling`, `alpine`, `opensuse/tumbleweed`) alongside Arch Linux ARM with live tag browsing and automated package/environment bootstrapping.

---

## 2. Source Requirements

- **REQ-ROOTFS-001:** Multi-Rootfs Container Isolation & Management
- **REQ-ROOTFS-002:** Dynamic Distro Acquisition & Layer Verification
- **REQ-PROV-001:** Zero-Terminal Multi-Distro System Bootstrapping (`/etc/os-release`)

---

## 3. Planned Scope & Acceptance Criteria

1. **Dynamic OCI Registry & Tag List Client (`core:proot`):**
   - Implement `OciRegistryClient` implementing OCI Distribution Spec v2 and Docker Hub Registry v2 API.
   - Handle anonymous OAuth2 Bearer token authentication flow via `Www-Authenticate` challenge headers (`realm`, `service`, `scope`).
   - Provide a curated catalog of official images (`library/ubuntu`, `library/debian`, `library/fedora`, `kalilinux/kali-rolling`, `library/alpine`, `opensuse/tumbleweed`, and `library/archlinux`) with live tag queries (`/v2/<repo>/tags/list` or Docker Hub tag endpoints).
   - Parse OCI Image Index / Docker Manifest List (`application/vnd.oci.image.index.v1+json`, `application/vnd.docker.distribution.manifest.list.v2+json`) and filter strictly for `architecture: "arm64"` / `os: "linux"`, rejecting non-aarch64 images.

2. **Multi-Layer Stream Extractor with Zstd & OCI Whiteouts:**
   - Download layers sequentially using blob digest endpoints (`/v2/<repo>/blobs/<digest>`) with streaming SHA256 integrity checks.
   - Extract layer streams supporting both `gzip` (`.tar.gz`) and `zstd` (`.tar.zst`) compression.
   - Implement OCI whiteout processing:
     - Explicit file removal: `.wh.<filename>` deletes existing file `<filename>` from lower layers.
     - Opaque directory reset: `.wh..wh..opq` clears all existing entries inside directory from lower layers.
   - Ensure PRoot `--link2symlink` emulation and Android permission constraints are preserved during layer extraction.

3. **Multi-Distro Bootstrapper (`/etc/os-release` Detection) & Wizard Integration:**
   - Implement `MultiDistroBootstrapper` that inspects `/etc/os-release` inside the extracted rootfs.
   - Generate package installation and user creation scripts tailored to the distro family:
     - `arch`: `pacman`
     - `debian` / `ubuntu` / `kali`: `apt-get`
     - `fedora`: `dnf`
     - `alpine`: `apk`
   - Configure non-root user with passwordless `sudo` (or `doas`), `/etc/resolv.conf`, `/etc/hosts`, and session environment (`PULSE_SERVER=unix:/run/user/1000/pulse/native`, `WAYLAND_DISPLAY=wayland-0`).
   - Integrate the OCI Distro & Tag catalog browser into Wizard Step 1 and the Dashboard "New Workspace" dialog.

4. **Verification & Testing:**
   - Comprehensive unit tests covering Bearer token auth parsing, manifest index filtering for `arm64`, whiteout handling, and distro detection.
   - Live download & extraction tests of an OCI image layer stream.

---

## 4. Dependencies & Gates

- **Prerequisites:** TASK-004, TASK-005, TASK-012, TASK-014.
- **Status:** Done.
- **Evidence:** [Verification Evidence](evidence/verification.md)
