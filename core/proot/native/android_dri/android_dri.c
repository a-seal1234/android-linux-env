/* -*- c-set-style: "K&R"; c-basic-offset: 8 -*-
 *
 * Android virtual DRM render node extension for PRoot (TASK-024).
 *
 * The Android untrusted_app SELinux domain cannot access /dev/dri (label
 * graphics_device), and the guest has no DRM render node. This extension makes
 * /dev/dri/renderD128 usable inside the guest:
 *
 *  - openat() on /dev/dri/renderD128 is routed (via a ProotRunner bind) to
 *    /dev/dma_heap/system, which the app is allowed to read; the open flags are
 *    forced to O_RDONLY because dma-buf heap nodes are mode 0444.
 *  - statx()/newfstatat()/fstat() on the node/the tracked fd report a character
 *    device with st_rdev = makedev(226, 128), matching the linux-dmabuf
 *    main_device advertised by the Wayland bridge.
 *  - DRM ioctls on the tracked fds are emulated (VERSION, GET_CAP, dumb-buffer
 *    allocation/mapping) so gbm_create_device() and Mesa's GBM probing succeed.
 *
 * Enable with ALW_ANDROID_DRI=1 in the environment.
 */

#include <errno.h>
#include <fcntl.h>
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <unistd.h>

#include "cli/note.h"
#include "extension/extension.h"
#include "syscall/syscall.h"
#include "syscall/sysnum.h"
#include "syscall/chain.h"
#include "tracee/mem.h"
#include "tracee/reg.h"

#define DRI_GUEST_PATH "/dev/dri/renderD128"
#define DRI_DEV_MAJOR  226
#define DRI_DEV_MINOR  128

/* --- Minimal DRM uapi (subset used by GBM/libdrm probing) --- */

struct drm_version {
	int version_major;
	int version_minor;
	int version_patchlevel;
	size_t name_len;
	char *name;
	size_t date_len;
	char *date;
	size_t desc_len;
	char *desc;
};

struct drm_get_cap {
	uint64_t capability;
	uint64_t value;
};

struct drm_mode_create_dumb {
	uint32_t height;
	uint32_t width;
	uint32_t bpp;
	uint32_t flags;
	uint32_t handle;
	uint32_t pitch;
	uint64_t size;
};

struct drm_mode_map_dumb {
	uint32_t handle;
	uint32_t pad;
	uint64_t offset;
};

struct drm_mode_destroy_dumb {
	uint32_t handle;
};

struct drm_gem_close {
	uint32_t handle;
	uint32_t pad;
};

/* Minimal KMS surface used by Mesa's kms_swrast screen creation. */
struct drm_mode_card_res {
	uint64_t fb_id_ptr;
	uint64_t crtc_id_ptr;
	uint64_t connector_id_ptr;
	uint64_t encoder_id_ptr;
	uint32_t count_fbs;
	uint32_t count_crtcs;
	uint32_t count_connectors;
	uint32_t count_encoders;
	uint32_t min_width;
	uint32_t max_width;
	uint32_t min_height;
	uint32_t max_height;
};

struct drm_mode_crtc {
	uint64_t set_connectors_ptr;
	uint32_t count_connectors;
	uint32_t crtc_id;
	uint32_t fb_id;
	uint32_t x;
	uint32_t y;
	uint32_t gamma_size;
	uint32_t mode_valid;
	uint32_t mode_clock;
	uint16_t mode_hdisplay;
	uint16_t mode_hsync_start;
	uint16_t mode_hsync_end;
	uint16_t mode_htotal;
	uint16_t mode_hskew;
	uint16_t mode_vdisplay;
	uint16_t mode_vsync_start;
	uint16_t mode_vsync_end;
	uint16_t mode_vtotal;
	uint16_t mode_vscan;
	uint32_t mode_flags;
	uint32_t mode_type;
	uint32_t mode_name[8];
};

struct drm_mode_fb_cmd2 {
	uint32_t fb_id;
	uint32_t width;
	uint32_t height;
	uint32_t pixel_format;
	uint32_t flags;
	uint32_t handles[4];
	uint32_t pitches[4];
	uint32_t offsets[4];
	uint64_t modifier[4];
};

struct drm_mode_fb_cmd {
	uint32_t fb_id;
	uint32_t width;
	uint32_t height;
	uint32_t pitch;
	uint32_t bpp;
	uint32_t depth;
	uint32_t handle;
};

#define DRM_IOCTL_BASE 'd'
#define DRM_IOCTL_VERSION            _IOWR(DRM_IOCTL_BASE, 0x00, struct drm_version)
#define DRM_IOCTL_GET_CAP            _IOWR(DRM_IOCTL_BASE, 0x0c, struct drm_get_cap)
#define DRM_IOCTL_SET_CLIENT_CAP     _IOW(DRM_IOCTL_BASE, 0x0d, struct drm_set_client_cap)
#define DRM_IOCTL_GEM_CLOSE          _IOW(DRM_IOCTL_BASE, 0x09, struct drm_gem_close)
#define DRM_IOCTL_MODE_CREATE_DUMB   _IOWR(DRM_IOCTL_BASE, 0xb2, struct drm_mode_create_dumb)
#define DRM_IOCTL_MODE_MAP_DUMB      _IOWR(DRM_IOCTL_BASE, 0xb3, struct drm_mode_map_dumb)
#define DRM_IOCTL_MODE_DESTROY_DUMB  _IOWR(DRM_IOCTL_BASE, 0xb4, struct drm_mode_destroy_dumb)
#define DRM_IOCTL_PRIME_HANDLE_TO_FD _IOWR(DRM_IOCTL_BASE, 0x2d, struct drm_prime_handle)
#define DRM_IOCTL_PRIME_FD_TO_HANDLE _IOWR(DRM_IOCTL_BASE, 0x2e, struct drm_prime_handle)
#define DRM_IOCTL_MODE_GETRESOURCES  _IOWR(DRM_IOCTL_BASE, 0xA0, struct drm_mode_card_res)
#define DRM_IOCTL_MODE_GETCRTC       _IOWR(DRM_IOCTL_BASE, 0xA1, struct drm_mode_crtc)
#define DRM_IOCTL_MODE_SETCRTC       _IOWR(DRM_IOCTL_BASE, 0xA2, struct drm_mode_crtc)
#define DRM_IOCTL_MODE_ADDFB         _IOWR(DRM_IOCTL_BASE, 0xAE, struct drm_mode_fb_cmd)
#define DRM_IOCTL_MODE_ADDFB2        _IOWR(DRM_IOCTL_BASE, 0xB8, struct drm_mode_fb_cmd2)
#define DRM_IOCTL_MODE_RMFB          _IOWR(DRM_IOCTL_BASE, 0xAF, uint32_t)
#define DRM_IOCTL_SET_MASTER         0x641e
#define DRM_IOCTL_DROP_MASTER        0x641f

/* Android DMA-BUF heap allocation (kernel uapi) */
struct dma_heap_allocation_data {
	uint64_t len;
	uint32_t fd;
	uint32_t fd_flags;
	uint64_t heap_flags;
};
#define DMA_HEAP_IOC_MAGIC 'H'
#define DMA_HEAP_IOCTL_ALLOC _IOWR(DMA_HEAP_IOC_MAGIC, 0x0, struct dma_heap_allocation_data)

#define PRIME_SCRATCH_BACKOFF 512

struct drm_prime_handle {
	uint32_t handle;
	uint32_t flags;
	int32_t fd;
};

struct drm_set_client_cap {
	uint64_t capability;
	uint64_t value;
};

#define DRM_CAP_DUMB_BUFFER        0x1
#define DRM_CAP_PRIME              0x5
#define DRM_CAP_TIMESTAMP_MONOTONIC 0x6
#define DRM_CAP_ADDFB2_MODIFIERS   0x10

/* --- Per-tracee virtual DRM fd tracking --- */

#define MAX_DRI_FDS 256
#define MAX_DRI_BOS 256

typedef struct {
	pid_t pid;
	int fd;
} DriFdEntry;

typedef struct {
	pid_t pid;
	int fd;            /* owning virtual DRM fd */
	uint32_t handle;
	uint32_t width;
	uint32_t height;
	uint32_t bpp;
	uint64_t size;
} DriBoEntry;

static DriFdEntry s_driFds[MAX_DRI_FDS];
static size_t s_driFdCount = 0;
static DriBoEntry s_driBos[MAX_DRI_BOS];
static size_t s_driBoCount = 0;
static uint32_t s_nextHandle = 1;
static pid_t s_pendingOpenPid = 0;
/* Pending stat-like syscall whose guest path was /dev/dri/renderD128 at enter.
 * kind: 1 = statx, 2 = newfstatat */
static pid_t s_pendingStatPid = 0;
static int s_pendingStatKind = 0;

/* Pending PRIME_HANDLE_TO_FD per tracee/thread: chained DMA_HEAP_IOCTL_ALLOC.
 * Hyprland exports dma-bufs from multiple threads concurrently, so this state
 * must be per-pid (a single global slot mis-associates chained completions and
 * wedges the tracer). */
#define MAX_PRIME_PENDING 64
typedef struct {
	pid_t pid;
	word_t arg;
	word_t scratch;
	uint32_t handle;
	bool active;
} PrimePending;

static PrimePending s_primePending[MAX_PRIME_PENDING];

static PrimePending *prime_state_for(pid_t pid, bool create)
{
	size_t i;
	PrimePending *free_slot = NULL;

	for (i = 0; i < MAX_PRIME_PENDING; i++) {
		if (s_primePending[i].active && s_primePending[i].pid == pid)
			return &s_primePending[i];
		if (!s_primePending[i].active && free_slot == NULL)
			free_slot = &s_primePending[i];
	}
	if (!create || free_slot == NULL)
		return NULL;
	memset(free_slot, 0, sizeof(*free_slot));
	free_slot->pid = pid;
	return free_slot;
}

static bool is_dri_path(const char *path)
{
	if (path == NULL)
		return false;
	return strcmp(path, DRI_GUEST_PATH) == 0;
}

static void remember_dri_fd(pid_t pid, int fd)
{
	size_t i;
	for (i = 0; i < s_driFdCount; i++) {
		if (s_driFds[i].pid == pid && s_driFds[i].fd == fd)
			return;
	}
	if (s_driFdCount < MAX_DRI_FDS) {
		s_driFds[s_driFdCount].pid = pid;
		s_driFds[s_driFdCount].fd = fd;
		s_driFdCount++;
	}
}

static void forget_dri_fd(pid_t pid, int fd)
{
	size_t i;
	for (i = 0; i < s_driFdCount; i++) {
		if (s_driFds[i].pid == pid && s_driFds[i].fd == fd) {
			s_driFds[i] = s_driFds[s_driFdCount - 1];
			s_driFdCount--;
			return;
		}
	}
}

static bool is_dri_fd(pid_t pid, int fd)
{
	size_t i;
	for (i = 0; i < s_driFdCount; i++) {
		if (s_driFds[i].pid == pid && s_driFds[i].fd == fd)
			return true;
	}
	return false;
}


/* A duplicated/inherited fd (dup, fork, exec) is not in the table: fall back to
 * resolving the tracee's fd through /proc to recognise the bound DMA-BUF heap. */
static bool fd_looks_like_dri(Tracee *tracee, int fd)
{
	char procPath[64];
	char target[PATH_MAX];
	ssize_t len;

	if (fd < 0)
		return false;

	snprintf(procPath, sizeof(procPath), "/proc/%d/fd/%d", (int) tracee->pid, fd);
	len = readlink(procPath, target, sizeof(target) - 1);
	if (len <= 0)
		return false;
	target[len] = '\0';

	if (strstr(target, "dma_heap") != NULL)
		return true;
	return false;
}

static DriBoEntry *find_bo(pid_t pid, int fd, uint32_t handle)
{
	size_t i;
	(void) fd;
	/* Handles are per-device, not per-fd: dup()/inherited descriptors refer to the
	 * same virtual DRM device, so look the BO up by tracee + handle only. */
	for (i = 0; i < s_driBoCount; i++) {
		if (s_driBos[i].pid == pid && s_driBos[i].handle == handle)
			return &s_driBos[i];
	}
	return NULL;
}

static DriBoEntry *add_bo(pid_t pid, int fd, uint32_t handle,
			  uint32_t width, uint32_t height, uint32_t bpp, uint64_t size)
{
	if (s_driBoCount >= MAX_DRI_BOS)
		return NULL;
	DriBoEntry *bo = &s_driBos[s_driBoCount++];
	bo->pid = pid;
	bo->fd = fd;
	bo->handle = handle;
	bo->width = width;
	bo->height = height;
	bo->bpp = bpp;
	bo->size = size;
	return bo;
}

/* --- stat spoofing helpers --- */

/* arm64 kernel struct stat: st_dev@0, st_ino@8, st_mode@16, st_nlink@20,
 * st_uid@24, st_gid@28, st_rdev@32. */
#define STAT_OFF_MODE  16
#define STAT_OFF_RDEV  32

/* struct statx: stx_mode@28 (u16), stx_rdev_major@128, stx_rdev_minor@132. */
#define STATX_OFF_MODE       28
#define STATX_OFF_RDEV_MAJOR 128
#define STATX_OFF_RDEV_MINOR 132

static void patch_stat_buffer(Tracee *tracee, word_t addr, bool is_statx)
{
	if (addr == 0)
		return;

	if (is_statx) {
		uint32_t major = DRI_DEV_MAJOR;
		uint32_t minor = DRI_DEV_MINOR;
		uint16_t mode = (uint16_t) (S_IFCHR | 0666);
		write_data(tracee, addr + STATX_OFF_MODE, &mode, sizeof(*&mode));
		write_data(tracee, addr + STATX_OFF_RDEV_MAJOR, &major, sizeof(*&major));
		write_data(tracee, addr + STATX_OFF_RDEV_MINOR, &minor, sizeof(*&minor));
	} else {
		uint32_t mode = (uint32_t) (S_IFCHR | 0666);
		uint64_t rdev = (uint64_t) makedev(DRI_DEV_MAJOR, DRI_DEV_MINOR);
		write_data(tracee, addr + STAT_OFF_MODE, &mode, sizeof(*&mode));
		write_data(tracee, addr + STAT_OFF_RDEV, &rdev, sizeof(*&rdev));
	}
}

/* --- DRM ioctl emulation, evaluated at syscall exit --- */

static void emulate_ioctl(Tracee *tracee, int fd, unsigned long request, word_t arg)
{
	word_t result = (word_t) -ENOTTY;
	uint32_t zero = 0;

	if (request == DRM_IOCTL_VERSION) {
		struct drm_version version;
		word_t namePtr = 0, datePtr = 0, descPtr = 0;
		size_t nameLen = 0;

		if (arg != 0 && read_data(tracee, &version, arg, sizeof(*&version)) >= 0) {
			namePtr = (word_t) (uintptr_t) version.name;
			nameLen = version.name_len;
			datePtr = (word_t) (uintptr_t) version.date;
			descPtr = (word_t) (uintptr_t) version.desc;
		}

		version.version_major = 1;
		version.version_minor = 0;
		version.version_patchlevel = 0;

		if (namePtr != 0 && nameLen >= 5) {
			write_data(tracee, namePtr, "vgem", 5);
			version.name_len = 5;
		} else {
			version.name_len = 5;
		}
		if (datePtr != 0 && version.date_len >= 9) {
			write_data(tracee, datePtr, "20260101", 9);
		} else {
			version.date_len = 9;
		}
		if (descPtr != 0 && version.desc_len >= 28) {
			const char *desc = "ALW virtual DRM render node";
			write_data(tracee, descPtr, desc, strlen(desc) + 1);
			version.desc_len = strlen(desc) + 1;
		} else {
			const char *desc = "ALW virtual DRM render node";
			version.desc_len = strlen(desc) + 1;
		}
		if (arg != 0)
			write_data(tracee, arg, &version, sizeof(*&version));
		result = 0;
	} else if (request == DRM_IOCTL_GET_CAP) {
		struct drm_get_cap cap;
		if (arg != 0 && read_data(tracee, &cap, arg, sizeof(*&cap)) >= 0) {
			if (cap.capability == DRM_CAP_DUMB_BUFFER)
				cap.value = 1;
			else if (cap.capability == DRM_CAP_PRIME)
				cap.value = 1 | 2; /* DRM_PRIME_CAP_IMPORT | DRM_PRIME_CAP_EXPORT */
			else if (cap.capability == DRM_CAP_TIMESTAMP_MONOTONIC)
				cap.value = 1;
			else if (cap.capability == DRM_CAP_ADDFB2_MODIFIERS)
				cap.value = 1;
			else
				cap.value = 0;
			write_data(tracee, arg, &cap, sizeof(*&cap));
			result = 0;
		}
	} else if (request == DRM_IOCTL_SET_CLIENT_CAP) {
		result = 0;
	} else if (request == DRM_IOCTL_MODE_CREATE_DUMB) {
		struct drm_mode_create_dumb dumb;
		if (arg != 0 && read_data(tracee, &dumb, arg, sizeof(*&dumb)) >= 0
		    && dumb.width > 0 && dumb.height > 0) {
			uint32_t bpp = dumb.bpp != 0 ? dumb.bpp : 32;
			dumb.pitch = dumb.width * bpp / 8;
			dumb.size = (uint64_t) dumb.pitch * dumb.height;
			dumb.handle = s_nextHandle++;
			add_bo(tracee->pid, fd, dumb.handle, dumb.width, dumb.height, bpp, dumb.size);
			write_data(tracee, arg, &dumb, sizeof(*&dumb));
			VERBOSE(tracee, 2, "android_dri: create_dumb %ux%u bpp=%u handle=%u size=%llu",
				dumb.width, dumb.height, bpp, dumb.handle,
				(unsigned long long) dumb.size);
			result = 0;
		}
	} else if (request == DRM_IOCTL_MODE_MAP_DUMB) {
		struct drm_mode_map_dumb map;
		if (arg != 0 && read_data(tracee, &map, arg, sizeof(*&map)) >= 0) {
			map.pad = 0;
			map.offset = 0;
			write_data(tracee, arg, &map, sizeof(*&map));
			result = 0;
		}
	} else if (request == DRM_IOCTL_MODE_DESTROY_DUMB) {
		struct drm_mode_destroy_dumb destroy;
		if (arg != 0 && read_data(tracee, &destroy, arg, sizeof(*&destroy)) >= 0) {
			DriBoEntry *bo = find_bo(tracee->pid, fd, destroy.handle);
			if (bo != NULL)
				bo->handle = 0;
			result = 0;
		}
	} else if (request == DRM_IOCTL_GEM_CLOSE) {
		struct drm_gem_close close_arg;
		if (arg != 0 && read_data(tracee, &close_arg, arg, sizeof(*&close_arg)) >= 0) {
			DriBoEntry *bo = find_bo(tracee->pid, fd, close_arg.handle);
			if (bo != NULL)
				bo->handle = 0;
			result = 0;
		}
	} else if (request == DRM_IOCTL_PRIME_HANDLE_TO_FD) {
		/* Export a dumb buffer as a real DMA-BUF: chain DMA_HEAP_IOCTL_ALLOC in the
		 * tracee so the freshly allocated fd lands in the tracee's fd table. */
		struct drm_prime_handle prime;
		if (arg != 0 && read_data(tracee, &prime, arg, sizeof(prime)) >= 0) {
			DriBoEntry *bo = find_bo(tracee->pid, fd, prime.handle);
			if (bo != NULL && bo->size > 0) {
				word_t sp = peek_reg(tracee, ORIGINAL, STACK_POINTER);
				word_t scratch = (sp - PRIME_SCRATCH_BACKOFF) & ~(word_t) 15;
				struct dma_heap_allocation_data alloc;
				int chainStatus;

				memset(&alloc, 0, sizeof(alloc));
				alloc.len = bo->size;
				alloc.fd_flags = O_RDWR | O_CLOEXEC;

				if (write_data(tracee, scratch, &alloc, sizeof(alloc)) < 0)
					return;

				chainStatus = register_chained_syscall(tracee, PR_ioctl,
					(word_t) fd, (word_t) DMA_HEAP_IOCTL_ALLOC,
					scratch, 0, 0, 0);
				if (chainStatus < 0) {
					VERBOSE(tracee, 2, "android_dri: chained alloc failed to register");
					poke_reg(tracee, SYSARG_RESULT, (word_t) -EINVAL);
					return;
				}

				force_chain_final_result(tracee, 0);
				{
					PrimePending *pending = prime_state_for(tracee->pid, true);
					if (pending == NULL) {
						VERBOSE(tracee, 2, "android_dri: no PRIME pending slot");
						poke_reg(tracee, SYSARG_RESULT, (word_t) -EINVAL);
						return;
					}
					pending->arg = arg;
					pending->scratch = scratch;
					pending->handle = prime.handle;
					pending->active = true;
				}
				VERBOSE(tracee, 2, "android_dri: chained DMA_HEAP_IOCTL_ALLOC for handle %u (size %llu)",
					prime.handle, (unsigned long long) bo->size);
				return;
			}
		}
		poke_reg(tracee, SYSARG_RESULT, (word_t) -EINVAL);
		return;
	} else if (request == DRM_IOCTL_MODE_GETRESOURCES) {
		struct drm_mode_card_res res;
		if (arg != 0 && read_data(tracee, &res, arg, sizeof(res)) >= 0) {
			/* One virtual CRTC, no connectors/encoders/fbs. */
			if (res.count_crtcs == 0 && res.count_connectors == 0
			    && res.count_encoders == 0 && res.count_fbs == 0) {
				res.count_crtcs = 1;
			}
			res.min_width = res.min_width ? res.min_width : 1;
			res.max_width = res.max_width ? res.max_width : 16384;
			res.min_height = res.min_height ? res.min_height : 1;
			res.max_height = res.max_height ? res.max_height : 16384;
			write_data(tracee, arg, &res, sizeof(res));
			VERBOSE(tracee, 2, "android_dri: GETRESOURCES crtcs=%u", res.count_crtcs);
			result = 0;
		}
	} else if (request == DRM_IOCTL_MODE_GETCRTC) {
		struct drm_mode_crtc crtc;
		if (arg != 0 && read_data(tracee, &crtc, arg, sizeof(crtc)) >= 0) {
			crtc.crtc_id = crtc.crtc_id ? crtc.crtc_id : 1;
			crtc.fb_id = 0;
			crtc.gamma_size = 0;
			crtc.mode_valid = 0;
			write_data(tracee, arg, &crtc, sizeof(crtc));
			result = 0;
		}
	} else if (request == DRM_IOCTL_MODE_SETCRTC) {
		result = 0;
	} else if (request == DRM_IOCTL_MODE_ADDFB || request == DRM_IOCTL_MODE_ADDFB2) {
		if (request == DRM_IOCTL_MODE_ADDFB) {
			struct drm_mode_fb_cmd fb;
			if (arg != 0 && read_data(tracee, &fb, arg, sizeof(fb)) >= 0) {
				fb.fb_id = 1;
				write_data(tracee, arg, &fb, sizeof(fb));
				result = 0;
			}
		} else {
			struct drm_mode_fb_cmd2 fb2;
			if (arg != 0 && read_data(tracee, &fb2, arg, sizeof(fb2)) >= 0) {
				fb2.fb_id = 1;
				write_data(tracee, arg, &fb2, sizeof(fb2));
				result = 0;
			}
		}
	} else if (request == DRM_IOCTL_MODE_RMFB) {
		result = 0;
	} else if (request == DRM_IOCTL_SET_MASTER || request == DRM_IOCTL_DROP_MASTER) {
		result = 0;
	} else {
		/* Unknown request (e.g. a genuine DMA_HEAP_IOCTL_ALLOC on the bound heap
		 * node): leave the kernel's result untouched. */
		VERBOSE(tracee, 2, "android_dri: pass-through ioctl 0x%lx on fd %d",
			request, fd);
		return;
	}

	(void) zero;
	poke_reg(tracee, SYSARG_RESULT, result);
}

/**
 * Handler for the android_dri extension.
 */
int android_dri_callback(Extension *extension, ExtensionEvent event,
			 intptr_t data1, intptr_t data2)
{
	Tracee *tracee = TRACEE(extension);

	(void) data1;
	(void) data2;

	switch (event) {
	case INITIALIZATION: {
		return 0;
	}

	case SYSCALL_ENTER_START: {
		Sysnum sysnum = get_sysnum(tracee, ORIGINAL);

		if (sysnum == PR_openat || sysnum == PR_open) {
			char path[PATH_MAX];
			Reg pathReg = (sysnum == PR_openat) ? SYSARG_2 : SYSARG_1;
			int pathStatus = get_sysarg_path(tracee, path, pathReg);
			VERBOSE(tracee, 2, "android_dri: open enter pid=%d sys=%d path=%s (st=%d)",
				(int) tracee->pid, (int) sysnum, pathStatus >= 0 ? path : "?", pathStatus);
			if (pathStatus >= 0 && is_dri_path(path)) {
				/* dma-buf heap nodes are mode 0444: force read-only open. */
				if (sysnum == PR_openat) {
					word_t flags = peek_reg(tracee, CURRENT, SYSARG_3);
					flags &= ~(word_t) (O_WRONLY | O_RDWR | O_CREAT | O_TRUNC);
					flags |= (word_t) (O_RDONLY | O_CLOEXEC);
					poke_reg(tracee, SYSARG_3, flags);
				} else {
					word_t flags = peek_reg(tracee, CURRENT, SYSARG_2);
					flags &= ~(word_t) (O_WRONLY | O_RDWR | O_CREAT | O_TRUNC);
					flags |= (word_t) (O_RDONLY | O_CLOEXEC);
					poke_reg(tracee, SYSARG_2, flags);
				}
				s_pendingOpenPid = tracee->pid;
				VERBOSE(tracee, 2, "android_dri: virtual DRM open detected");
			}
		}

		/* Record stat-like syscalls on the virtual node; proot rewrites the path
		 * pointer before the exit stop, so the check must happen at entry. */
		if (sysnum == PR_statx || sysnum == PR_newfstatat) {
			char path[PATH_MAX];
			Reg pathReg = (sysnum == PR_statx) ? SYSARG_2 : SYSARG_2;
			if (get_sysarg_path(tracee, path, pathReg) >= 0 && is_dri_path(path)) {
				s_pendingStatPid = tracee->pid;
				s_pendingStatKind = (sysnum == PR_statx) ? 1 : 2;
			}
		}
		break;
	}

	case SYSCALL_EXIT_START: {
		Sysnum sysnum = get_sysnum(tracee, MODIFIED);
		word_t result = peek_reg(tracee, CURRENT, SYSARG_RESULT);

		if (s_pendingOpenPid == tracee->pid) {
			s_pendingOpenPid = 0;
			if ((int) result >= 0) {
				remember_dri_fd(tracee->pid, (int) result);
				VERBOSE(tracee, 2, "android_dri: tracked virtual DRM fd %d (pid %d)",
					(int) result, (int) tracee->pid);
			} else {
				VERBOSE(tracee, 2, "android_dri: virtual DRM open failed result=%d", (int) result);
			}
		}

		if (sysnum == PR_close && (int) result == 0) {
			int fd = (int) peek_reg(tracee, ORIGINAL, SYSARG_1);
			forget_dri_fd(tracee->pid, fd);
		}

		if (sysnum == PR_ioctl) {
			int fd = (int) peek_reg(tracee, ORIGINAL, SYSARG_1);
			if (!is_dri_fd(tracee->pid, fd) && fd_looks_like_dri(tracee, fd)) {
				remember_dri_fd(tracee->pid, fd);
				VERBOSE(tracee, 2, "android_dri: promoted duplicated fd %d to virtual DRM (pid %d)",
					fd, (int) tracee->pid);
			}
			if (is_dri_fd(tracee->pid, fd)) {
				VERBOSE(tracee, 2, "android_dri: ioctl on virtual DRM fd %d", fd);
				unsigned long request = (unsigned long) peek_reg(tracee, ORIGINAL, SYSARG_2);
				word_t arg = peek_reg(tracee, ORIGINAL, SYSARG_3);
				emulate_ioctl(tracee, fd, request, arg);
			}
		}

		if (s_pendingStatPid == tracee->pid) {
			int kind = s_pendingStatKind;
			s_pendingStatPid = 0;
			s_pendingStatKind = 0;
			if ((int) result == 0) {
				if (kind == 1) {
					/* statx(dirfd, path, flags, mask, buf) */
					word_t buf = peek_reg(tracee, ORIGINAL, SYSARG_5);
					patch_stat_buffer(tracee, buf, true);
				} else if (kind == 2) {
					/* newfstatat(dirfd, path, buf, flags) */
					word_t buf = peek_reg(tracee, ORIGINAL, SYSARG_3);
					patch_stat_buffer(tracee, buf, false);
				}
			}
		}

		/* fstat(fd, buf) on a tracked virtual DRM fd */
		if (sysnum == PR_fstat && (int) result == 0) {
			int fd = (int) peek_reg(tracee, CURRENT, SYSARG_1);
			if (is_dri_fd(tracee->pid, fd)) {
				VERBOSE(tracee, 2, "android_dri: ioctl on virtual DRM fd %d", fd);
				word_t buf = peek_reg(tracee, CURRENT, SYSARG_2);
				patch_stat_buffer(tracee, buf, false);
			}
		}
		break;
	}

	case SYSCALL_CHAINED_EXIT: {
		PrimePending *pending = prime_state_for(tracee->pid, false);
		if (pending != NULL) {
			struct dma_heap_allocation_data alloc;
			struct drm_prime_handle prime;

			if (read_data(tracee, &alloc, pending->scratch, sizeof(alloc)) >= 0
			    && read_data(tracee, &prime, pending->arg, sizeof(prime)) >= 0) {
				if ((int32_t) alloc.fd > 0) {
					prime.handle = pending->handle;
					prime.fd = (int32_t) alloc.fd;
					write_data(tracee, pending->arg, &prime, sizeof(prime));
					VERBOSE(tracee, 2, "android_dri: exported DMA-BUF fd %d for handle %u",
						prime.fd, pending->handle);
				}
			}
			pending->active = false;
			pending->pid = 0;
		}
		break;
	}

	default:
		break;
	}

	return 0;
}
