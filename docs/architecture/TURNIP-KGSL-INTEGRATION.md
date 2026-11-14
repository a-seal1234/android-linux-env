# Turnip & KGSL Integration Guide

**Status:** Approved Specification (Phase 0)  
**Document:** `docs/architecture/TURNIP-KGSL-INTEGRATION.md`  
**Target Hardware:** Qualcomm Snapdragon 8s Gen 4 (Adreno 825)  
**Kernel Interface:** Linux KGSL (`/dev/kgsl-3d0`)  
**Graphics API:** Vulkan 1.3, OpenGL ES 3.2, OpenGL 4.6 (via Zink)  

---

## 1. Qualcomm Adreno 825 & KGSL Overview

The Qualcomm Snapdragon 8s Gen 4 features the Adreno 825 GPU. On Android devices (including Xiaomi HyperOS), the Linux kernel graphics stack does not utilize desktop DRM/KMS drivers by default; instead, it exposes the proprietary Qualcomm **KGSL (Kernel Graphics Support Layer)** driver via character device node:
`/dev/kgsl-3d0`

Mesa's **Turnip** driver is an open-source Vulkan driver for Qualcomm Adreno hardware. Turnip contains native backend support for communicating directly with `/dev/kgsl-3d0` in user-space, bypassing Android's proprietary libvulkan.so and providing full desktop Vulkan 1.3 support directly to Wayland compositors (Hyprland) and Linux applications.

```
+--------------------------------------------------------------------------+
|                     Linux Applications & Compositor                      |
|                 (Hyprland, Chromium, VKCube, Blender)                    |
+------------------------------------+-------------------------------------+
                                     | Vulkan API calls
                                     v
+--------------------------------------------------------------------------+
|                       Mesa Turnip Driver (freedreno_icd)                  |
|                 (Loaded from /opt/turnip/lib/libvulkan_freedreno.so)      |
+------------------------------------+-------------------------------------+
                                     | KGSL ioctls (IOCTL_KGSL_GPU_COMMAND)
                                     v
+--------------------------------------------------------------------------+
|                 Bound Linux Kernel Device Node: /dev/kgsl-3d0            |
+------------------------------------+-------------------------------------+
                                     | Direct hardware dispatch
                                     v
+--------------------------------------------------------------------------+
|                  Qualcomm Adreno 825 Hardware (Physical GPU)              |
+--------------------------------------------------------------------------+
```

---

## 2. Shared External Driver Store Architecture

Rather than installing Mesa into individual guest rootfs installations (which risks pacman conflicts during updates and duplicates large driver binaries across multi-rootfs instances), ALW implements a **Shared Driver Store** under internal storage:
`files/drivers/turnip/<version>/`

This single store is mounted read-only (`:ro`) across all rootfs instances, minimizing flash storage overhead and enabling instantaneous one-tap driver switching:

```
<app_internal_data>/files/drivers/
├── turnip/
│   ├── v24.3.0/
│   │   ├── manifest.json
│   │   ├── lib/
│   │   │   ├── libvulkan_freedreno.so
│   │   │   ├── libGLX_mesa.so
│   │   │   └── dri/
│   │   │       ├── zink_dri.so
│   │   │       └── kgsl_dri.so
│   │   └── share/
│   │       └── vulkan/
│   │           └── icd.d/
│   │               └── freedreno_icd.aarch64.json
│   └── v25.0.0-devel/
└── adreno-proprietary/
    └── v757-adreno825/
        ├── libvulkan_adreno.so
        └── share/vulkan/icd.d/adreno_icd.aarch64.json
```

### 2.1 Remote Fetching & Cryptographic Verification
1. **GitHub Releases Integration:**
   - Fetches verified Turnip driver tarballs directly from upstream releases (e.g. `K11MCH1/Mesa-Turnip-Driver` or ALW official driver mirrors).
   - Downloads accompanying `SHA256SUMS` or extracts declared checksums from release metadata.
2. **SHA256 Verification:**
   - Verifies archive integrity before unpacking into `files/drivers/turnip/<version>/`.
   - Rejects corrupted or tampered driver tarballs.
3. **Dynamic Runtime Binding:**
   - Injects the active driver into the container via:
     - `VK_ICD_FILENAMES=/opt/turnip/share/vulkan/icd.d/freedreno_icd.aarch64.json`
     - `LIBGL_DRIVERS_PATH=/opt/turnip/lib/dri`
     - `LD_LIBRARY_PATH=/opt/turnip/lib`
     - `MESA_LOADER_DRIVER_OVERRIDE=kgsl` (or `zink` when paired with proprietary Vulkan)

### Driver Package Manifest (`manifest.json`)
```json
{
  "id": "turnip-v24.3.0",
  "name": "Mesa Turnip 24.3.0 (Adreno 8xx Optimized)",
  "version": "24.3.0",
  "sourceUrl": "https://github.com/K11MCH1/Mesa-Turnip-Driver/releases/download/v24.3.0/turnip-v24.3.0.tar.gz",
  "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "minKernelVersion": "5.15.0",
  "supportedSoCs": ["Snapdragon 8 Gen 3", "Snapdragon 8s Gen 4"],
  "recommendedOverrides": {
    "TU_DEBUG": "noconform",
    "MESA_LOADER_DRIVER_OVERRIDE": "kgsl"
  }
}
```

---

## 3. Dynamic Environment Variable Injection

When starting a session with `turnip-kgsl` enabled in `StackProfile`, `session-core` mounts the driver package to `/opt/turnip` and exports:

```bash
# Vulkan ICD registration
export VK_ICD_FILENAMES=/opt/turnip/share/vulkan/icd.d/freedreno_icd.aarch64.json

# OpenGL DRI and loader paths
export LIBGL_DRIVERS_PATH=/opt/turnip/lib/dri
export LD_LIBRARY_PATH=/opt/turnip/lib:$LD_LIBRARY_PATH

# Force KGSL backend rather than DRM
export MESA_LOADER_DRIVER_OVERRIDE=kgsl

# Turnip optimization flags
export TU_DEBUG=noconform
export MESA_VK_WSI_PRESENT_MODE=mailbox
```

---

## 4. Driver Modes & Fallback Matrix (Adreno 825 / A8xx Flexibility)

Because the Xiaomi Pad 8 features the newer **Qualcomm Adreno 825 (A8xx series)** GPU, upstream Mesa Turnip support and stability may vary across Mesa release cycles and kernel driver revisions. To guarantee maximum flexibility and uninterrupted productivity, the external GPU Driver Library supports two hardware-accelerated modes in addition to virtualized and software fallbacks:

1. **Open-Source Mesa Turnip Driver (`turnip_kgsl`):**
   Standard `.tar.gz` driver package containing Mesa's `libvulkan_freedreno.so` communicating directly with `/dev/kgsl-3d0`. Provides native Vulkan 1.3 for Hyprland, Wayland compositors, and modern Vulkan-native toolchains.
2. **Proprietary Qualcomm Adreno Driver + Zink (`adreno_proprietary_zink`):**
   Extracted Android vendor binary (`libvulkan_adreno.so` from device firmware `/vendor/lib64/hw/vulkan.adreno.so`). While proprietary drivers do not natively implement desktop GLX/DRI interfaces, ALW pairs the proprietary Vulkan ICD with **Mesa Zink** (`MESA_LOADER_DRIVER_OVERRIDE=zink`) to translate desktop OpenGL 4.6 calls into proprietary Adreno Vulkan calls. This serves as an alternative acceleration path if a specific Mesa Turnip build exhibits rendering anomalies on A8xx hardware.
3. **Mesa LLVM Software Rasterizer (`llvmpipe`):**
   CPU software rendering fallback used by one-tap **Safe Mode** (`LabWC + llvmpipe`) to guarantee desktop accessibility during driver crashes.
4. **Virtual 3D Driver (`virgl` / `venus`):**
   For hypervisor or container test environments.

| Driver Preset ID | Vulkan Driver | OpenGL Driver | Target Hardware Use Case |
|---|---|---|---|
| `turnip_kgsl` | Mesa Turnip (`libvulkan_freedreno.so`) | Freedreno / Turnip native | Default for Adreno 825 (100% Native Vulkan 1.3) |
| `adreno_proprietary_zink` | Qualcomm Adreno (`libvulkan_adreno.so`) | Mesa Zink over Adreno Vulkan | Alternative hardware path if Turnip A8xx regresses |
| `llvmpipe` | None (Software) | Mesa LLVMpipe (CPU) | Safe Mode & crash recovery (100% stable) |
| `virgl` | VirGL / Venus | VirGL GLES wrapper | Virtualized test environments |

---

## 5. Verification & Diagnostics

Inside the running workstation session, driver acceleration is verified using standard diagnostic tools:

```bash
# 1. Verify Vulkan ICD and Device Identification
vulkaninfo --summary

# Expected Output on Xiaomi Pad 8:
# Devices:
# ========
# GPU0:
#         apiVersion         = 1.3.296
#         driverVersion      = 24.3.0
#         vendorID           = 0x5143 (Qualcomm)
#         deviceID           = 0x0825 (Adreno 825)
#         deviceType         = PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU
#         deviceName         = Turnip Adreno (TM) 825

# 2. Test 3D rendering at 144 FPS
vkcube --present_mode 0
```
