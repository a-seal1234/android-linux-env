# PRoot `android_dri` extension (TASK-024)

Virtual `/dev/dri/renderD128` support for the unrooted Android sandbox.

The Android `untrusted_app` SELinux domain cannot access `/dev/dri` (label
`graphics_device`), and there is no accessible DRM render node inside the guest.
This PRoot extension makes the node usable:

- `openat()` on `/dev/dri/renderD128` is routed by a `ProotRunner` bind to
  `/dev/dma_heap/system` (SELinux-allowed, mode 0444); the extension forces
  `O_RDONLY|O_CLOEXEC` on the open.
- `statx` / `newfstatat` / `fstat` report `S_IFCHR | 0666`, `st_rdev = makedev(226,128)`.
- DRM ioctls on tracked fds are emulated: `VERSION` (`vgem`), `GET_CAP`
  (`DUMB_BUFFER=1`, `PRIME=import|export`, `TIMESTAMP_MONOTONIC=1`,
  `ADDFB2_MODIFIERS=1`), `MODE_CREATE_DUMB`, `MODE_MAP_DUMB`,
  `MODE_DESTROY_DUMB`, `GEM_CLOSE`, plus the minimal KMS surface
  (`MODE_GETRESOURCES`/`GETCRTC`/`SETCRTC`/`ADDFB`/`ADDFB2`/`RMFB`,
  `SET_MASTER`/`DROP_MASTER`); `PRIME_HANDLE_TO_FD` chains a real
  `DMA_HEAP_IOCTL_ALLOC` **in the tracee** so the dma-buf fd lands in the
  tracee's fd table. `mmap()` on the virtual node at a MAP_DUMB offset is
  rewritten to the BO's backing fd so CPU renderers write into the exported
  buffer. Pending chained allocations are tracked **per tracee**
  (compositors export dma-bufs from multiple threads concurrently). Unknown
  ioctls pass through untouched.

## Enable

Set `ALW_ANDROID_DRI=1` in the PRoot runner environment (`ProotRunner` does this)
and bind the heap node, plus the listable fakefs directory:

```
-b <fakefs>/dev/dri:/dev/dri
-b /dev/dma_heap/system:/dev/dri/renderD128
```

The Wayland bridge additionally advertises `main_device = makedev(226,128)` in
`zwp_linux_dmabuf_feedback_v1`, matching the virtual node.

## Build recipe

The vendored `lib_proot.so` is built from proot v5.4.0 (`bd5a5f6`) with:

1. `docs/tasks/TASK-023/evidence/proot-android16-sigsys.patch` (SIGSYS suppression).
2. `docs/tasks/TASK-024/evidence/proot-android-dri.patch` (this extension + build wiring).

Steps (NDK r27c):

```sh
git clone https://github.com/proot-me/proot.git && cd proot && git checkout bd5a5f6
git apply <repo>/docs/tasks/TASK-023/evidence/proot-android16-sigsys.patch
git apply <repo>/docs/tasks/TASK-024/evidence/proot-android-dri.patch

# talloc (static, shimmed replace.h) — see patch for the GNUmakefile changes
# then:
cd src
make proot \
  CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang \
  LD=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang \
  STRIP=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip \
  OBJCOPY=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objcopy \
  OBJDUMP=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump
cp proot <repo>/core/proot/src/main/jniLibs/arm64-v8a/lib_proot.so
```

The talloc build shim (`talloc_shim/replace.h`) and `CPPFLAGS` additions are
included in the patch. `lib_loader.so` (vendored) is reused unchanged via
`PROOT_LOADER`.

## Guest expectations

- `libalw_drm.so` (`/etc/ld.so.preload`) interposes `drmGetRenderDeviceNameFromFd`,
  `drmGetDeviceNameFromFd2`, `drmIsMaster`, `drmGetDevice2` and
  `drmGetDeviceFromDevId` so libdrm resolves the sandboxed node (see
  `ArchProvisioningScriptBuilder`). The synthetic `drmDevice` carries zeroed
  PCI businfo/deviceinfo so libdrm's `drmDevicesEqual()` is safe.
- The compositor process must run with `MESA_LOADER_DRIVER_OVERRIDE=kms_swrast`
  and **no** `GALLIUM_DRIVER` or `LIBGL_ALWAYS_SOFTWARE=1`: both make
  `eglInitialize()` fail (`EGL_NOT_INITIALIZED`) on the virtual `vgem` node
  (verified with an EGL/GBM probe).
- `gbm_create_device()` and `gbm_bo_get_fd()` (PRIME export) are verified working
  on the virtual node with Mesa 27 (llvmpipe/swrast).
