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

## 2. External Driver Library Management

Rather than installing Mesa into the guest rootfs (which risks pacman package conflicts during system updates), ALW manages driver versions as external packages outside the rootfs:

```
/data/user/0/<package-id>/files/drivers/
└── turnip-mesa-24.3-adreno825/
    ├── manifest.json
    ├── lib/
    │   ├── libvulkan_freedreno.so
    │   ├── libGLX_mesa.so
    │   └── dri/
    │       ├── zink_dri.so
    │       └── kgsl_dri.so
    └── share/
        └── vulkan/
            └── icd.d/
                └── freedreno_icd.aarch64.json
```

### Driver Package Manifest (`manifest.json`)
```json
{
  "id": "turnip-mesa-24.3-adreno825",
  "name": "Mesa Turnip 24.3.0 (Adreno 8xx Optimized)",
  "version": "24.3.0",
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

## 4. Driver Modes & Fallback Matrix

| Mode | Driver Backend | Target Use Case | Performance |
|---|---|---|---|
| `turnip_kgsl` | Turnip -> `/dev/kgsl-3d0` | Default for Hyprland, Vulkan games, Modern IDEs | Native GPU (100%) |
| `turnip_zink` | Zink (OpenGL over Turnip Vulkan) | Legacy OpenGL 3.x/4.x desktop applications | ~85-95% Native |
| `llvmpipe` | Mesa LLVM Software Rasterizer | One-Tap **Safe Mode**; GPU recovery | CPU Software (~5-15%) |
| `virgl` | Virtual 3D driver | Running inside virtualization / hypervisors | ~60-80% Native |

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
