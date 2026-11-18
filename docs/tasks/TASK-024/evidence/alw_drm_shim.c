/* TASK-024: libdrm interposers for the virtual DRM render node.
 * /dev/dri/renderD128 is backed by /dev/dma_heap/system inside the sandbox, so
 * libdrm cannot resolve the device via /proc fdinfo/sysfs/udev. Report the
 * canonical render node for DMA-BUF-heap fds so Aquamarine/KWin accept the
 * allocator (CGBMAllocator::create -> drmGetCap + gbm_create_device).
 * Self-contained (no libdrm headers required). */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <dlfcn.h>

typedef struct {
	char **nodes;
	int available_nodes;
	int bustype;
	void *businfo;
	void *deviceinfo;
} alw_drmDevice;

#define ALW_DRM_NODE_RENDER 2
#define ALW_DRM_NODE_MAX    3

static int alw_is_virtual_drm_fd(int fd)
{
	char proc[64];
	char link[256];
	ssize_t n;

	if (fd < 0)
		return 0;
	snprintf(proc, sizeof(proc), "/proc/self/fd/%d", fd);
	n = readlink(proc, link, sizeof(link) - 1);
	if (n <= 0)
		return 0;
	link[n] = '\0';
	return strstr(link, "dma_heap") != NULL || strstr(link, "renderD128") != NULL;
}

static alw_drmDevice *alw_make_virtual_device(void)
{
	alw_drmDevice *d = calloc(1, sizeof(*d));
	if (!d)
		return NULL;
	d->nodes = calloc(ALW_DRM_NODE_MAX, sizeof(char *));
	if (!d->nodes) {
		free(d);
		return NULL;
	}
	d->nodes[ALW_DRM_NODE_RENDER] = strdup("/dev/dri/renderD128");
	d->available_nodes = 1 << ALW_DRM_NODE_RENDER;
	/* drmDevicesEqual() memcmp()s the bus/device info for a given bustype:
	 * provide zeroed, valid buffers and PCI bus type so it never dereferences
	 * NULL (Hyprland DRM::sameGpu -> drmDevicesEqual). */
	d->bustype = 2 /* DRM_BUS_PCI */;
	d->businfo = calloc(1, 64);
	d->deviceinfo = calloc(1, 64);
	return d;
}

char *drmGetRenderDeviceNameFromFd(int fd)
{
	static char *(*real_fn)(int) = NULL;
	if (alw_is_virtual_drm_fd(fd))
		return strdup("/dev/dri/renderD128");
	if (!real_fn)
		real_fn = dlsym(RTLD_NEXT, "drmGetRenderDeviceNameFromFd");
	return real_fn ? real_fn(fd) : NULL;
}

char *drmGetDeviceNameFromFd2(int fd)
{
	static char *(*real_fn)(int) = NULL;
	if (alw_is_virtual_drm_fd(fd))
		return strdup("/dev/dri/renderD128");
	if (!real_fn)
		real_fn = dlsym(RTLD_NEXT, "drmGetDeviceNameFromFd2");
	return real_fn ? real_fn(fd) : NULL;
}

int drmGetDevice2(int fd, unsigned int flags, void **device)
{
	static int (*real_fn)(int, unsigned int, void **) = NULL;
	if (alw_is_virtual_drm_fd(fd)) {
		if (!device)
			return -1;
		*device = alw_make_virtual_device();
		return *device ? 0 : -1;
	}
	if (!real_fn)
		real_fn = dlsym(RTLD_NEXT, "drmGetDevice2");
	return real_fn ? real_fn(fd, flags, device) : -1;
}

int drmGetDeviceFromDevId(unsigned long dev_id, unsigned int flags, void **device)
{
	(void) dev_id;
	(void) flags;
	if (!device)
		return -1;
	*device = alw_make_virtual_device();
	return *device ? 0 : -1;
}

int drmIsMaster(int fd)
{
	(void) fd;
	return 0;
}
