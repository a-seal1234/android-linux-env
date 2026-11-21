# 2026-11-21 — freedreno (KGSL) as a Zink-free guest GL backend

Probe run on the device (`Xiaomi Pad 8`, app `com.alw.workstation`) from inside the
PRoot guest rootfs (`archlinux-default`, Mesa `27.0.0-arch1.92`), executed via
`run-as` with the same mounts the app uses:

```
$ GALLIUM_DRIVER=kgsl MESA_LOADER_DRIVER_OVERRIDE=kgsl EGL_PLATFORM=surfaceless eglinfo
EGL driver name: kgsl
OpenGL core profile version: 4.6 (Core Profile) Mesa 27.0.0-arch1.92 (git-b84f02a451)
OpenGL ES profile vendor: freedreno
OpenGL ES profile renderer: Adreno (TM) 825
OpenGL ES profile version: OpenGL ES 3.2 Mesa 27.0.0-arch1.92 (git-b84f02a451)
OpenGL ES profile shading language version: OpenGL ES GLSL ES 3.20
```

Guest package facts:

```
/usr/lib/dri/kgsl_dri.so -> libdril_dri.so      (present)
/usr/lib/dri/msm_dri.so  -> libdril_dri.so      (present)
/usr/lib/libgallium-27.0.0-arch1.92.so          (22894600 bytes, contains "kgsl"/"a6xx"/"freedreno")
/dev/kgsl-3d0                                   (crw-rw-rw-, gpu_device)
```

The compositor's own path (GBM EGL on the PRoot virtual DRM node, `ALW_ANDROID_DRI=1`,
`/dev/dri/renderD128`) initializes the same way — this is what allows `FREEDRENO_KGSL`
to skip the `kms_swrast` demotion:

```
$ EGL_PLATFORM=gbm GALLIUM_DRIVER=kgsl MESA_LOADER_DRIVER_OVERRIDE=kgsl eglinfo
GBM platform:
EGL API version: 1.5
EGL driver name: kgsl
OpenGL ES profile renderer: Adreno (TM) 825
OpenGL ES profile version: OpenGL ES 3.2 Mesa 27.0.0-arch1.92 (git-b84f02a451)
```

## Why it matters

* No Vulkan layer for GL: the app talks to `/dev/kgsl-3d0` through freedreno Gallium,
  so Zink's WSI / explicit-sync fence path (the TASK-035 artifact suspect) is gone.
* Higher GLES surface: **ES 3.2** here vs **ES 3.1** through Zink, plus GL 4.6 core.
* Turnip is untouched for Vulkan applications (`VK_ICD_FILENAMES` still exported).

## Live session state observed the same day (context)

The running session had the compositor on Zink, contrary to the shipped default:

```
$ tr '\0' '\n' < /proc/8097/environ | grep -E 'MESA|GALLIUM|COMPOSITOR'
MESA_LOADER_DRIVER_OVERRIDE=zink
GALLIUM_DRIVER=zink
ALW_COMPOSITOR_ZINK=1
ALW_GL_BACKEND=zink
TU_DEBUG=noconform          # profile override dropped "noubwc"
```

`files/arch-xiaomi-pad-8.json` pins `TU_DEBUG=noconform`, `MESA_LOADER_DRIVER_OVERRIDE=zink`
and `GALLIUM_DRIVER=zink` in `gpu.environmentOverrides`, which is applied after the
generated defaults.

## Not yet measured

FPS / frame-time of freedreno vs Zink under the real session (drag, video, Quickshell
overlay). The backend is selectable (`gpu.driverMode = "freedreno_kgsl"` or
`ALW_GL_BACKEND=freedreno`) but its default-on decision still needs that A/B.
