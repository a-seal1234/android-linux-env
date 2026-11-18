#include <stdio.h>
#include <fcntl.h>
#include <unistd.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/ioctl.h>
#include <gbm.h>

int main(void) {
    int fd = open("/dev/dri/renderD128", O_RDWR | O_CLOEXEC);
    printf("open(/dev/dri/renderD128, O_RDWR) = %d\n", fd);
    if (fd < 0) return 1;

    struct gbm_device *dev = gbm_create_device(fd);
    printf("gbm_create_device() = %p\n", (void *)dev);
    if (dev == NULL) return 2;

    struct gbm_bo *bo = gbm_bo_create(dev, 320, 240, GBM_FORMAT_ARGB8888,
                                      GBM_BO_USE_RENDERING | GBM_BO_USE_LINEAR);
    printf("gbm_bo_create(320x240 ARGB8888) = %p\n", (void *)bo);
    if (bo) {
        int exportFd;
        printf("  stride=%u height=%u\n", gbm_bo_get_stride(bo), gbm_bo_get_height(bo));
        exportFd = gbm_bo_get_fd(bo);
        printf("  gbm_bo_get_fd = %d\n", exportFd);
        if (exportFd >= 0) {
            size_t len = (size_t) gbm_bo_get_stride(bo) * gbm_bo_get_height(bo);
            void *map = mmap(NULL, len, PROT_READ | PROT_WRITE, MAP_SHARED, exportFd, 0);
            printf("  mmap(dmabuf fd) = %s\n", map == MAP_FAILED ? "FAILED" : "OK");
            if (map != MAP_FAILED) { memset(map, 0x5A, len); munmap(map, len); }
            close(exportFd);
        }
        gbm_bo_destroy(bo);
    }
    gbm_device_destroy(dev);
    printf("GBM_PROBE_OK\n");
    return 0;
}
