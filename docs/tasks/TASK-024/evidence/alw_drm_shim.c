/* TASK-024: libdrm interposers for the virtual DRM render node.
 * libdrm resolves node paths from /proc fdinfo and sysfs, which do not exist for
 * the sandboxed node backed by /dev/dma_heap/system. Report the canonical render
 * node for DMA-BUF-heap-backed fds so Aquamarine/KWin accept the allocator. */
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/sysmacros.h>
#include <xf86drm.h>

static int alw_is_virtual_drm_fd(int fd)
{
	char proc[64];
	char link[256];
	ssize_t n;

	snprintf(proc, sizeof(proc), "/proc/self/fd/%d", fd);
	n = readlink(proc, link, sizeof(link) - 1);
	if (n <= 0)
		return 0;
	link[n] = '\0';
	return strstr(link, "dma_heap") != NULL || strstr(link, "renderD128") != NULL;
}

char *drmGetRenderDeviceNameFromFd(int fd)
{
	if (!alw_is_virtual_drm_fd(fd))
		return NULL;
	return strdup("/dev/dri/renderD128");
}

char *drmGetDeviceNameFromFd2(int fd)
{
	if (!alw_is_virtual_drm_fd(fd))
		return NULL;
	return strdup("/dev/dri/renderD128");
}

int drmIsMaster(int fd)
{
	(void) fd;
	return 0;
}

/* libdrm cannot resolve the sandboxed device id (no sysfs/udev entry): return a
 * synthetic device pointing at the virtual render node so Aquamarine can open it
 * and build a GBM allocator on top. */
int drmGetDeviceFromDevId(dev_t dev_id, unsigned int flags, drmDevicePtr *device)
{
	(void) dev_id;
	(void) flags;
	if (device == NULL)
		return -1;

	drmDevicePtr d = calloc(1, sizeof(*d));
	if (d == NULL)
		return -1;

	d->nodes = calloc(DRM_NODE_MAX, sizeof(char *));
	if (d->nodes == NULL) {
		free(d);
		return -1;
	}
	d->nodes[DRM_NODE_RENDER] = strdup("/dev/dri/renderD128");
	d->available_nodes = 1u << DRM_NODE_RENDER;
	if (d->nodes[DRM_NODE_RENDER] == NULL) {
		free(d->nodes);
		free(d);
		return -1;
	}
	*device = d;
	return 0;
}
