/*
 * alw-dmabuf-test — TASK-024 end-to-end DMA-BUF client (raw Wayland protocol).
 *
 * Allocates a real DMA-BUF from the Android system heap, wraps it in a
 * zwp_linux_dmabuf_v1 wl_buffer and presents it to the ALW Wayland bridge
 * (wayland-0). If the bridge imports the buffer the screen fills with the
 * generated gradient.
 */
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

/* --- DMA-BUF heap uapi --- */
struct dma_heap_allocation_data {
	uint64_t len;
	uint32_t fd;
	uint32_t fd_flags;
	uint64_t heap_flags;
};
#define DMA_HEAP_IOC_MAGIC 'H'
#define DMA_HEAP_IOCTL_ALLOC _IOWR(DMA_HEAP_IOC_MAGIC, 0x0, struct dma_heap_allocation_data)

#define DRM_FORMAT_ARGB8888 0x34325241u

#define W 640
#define H 480
#define STRIDE (W * 4)

static int g_sock = -1;

struct msg {
	uint32_t obj;
	uint16_t op;
	uint16_t size;
	uint8_t payload[1024];
};

static uint32_t pad4(uint32_t n) { return (n + 3) & ~3u; }

static void msg_init(struct msg *m, uint32_t obj, uint16_t op)
{
	memset(m, 0, sizeof(*m));
	m->obj = obj;
	m->op = op;
	m->size = 8;
}
static void msg_u32(struct msg *m, uint32_t v)
{
	memcpy(&m->payload[m->size - 8], &v, 4);
	m->size += 4;
}
static void msg_str(struct msg *m, const char *s)
{
	uint32_t len = (uint32_t) strlen(s) + 1;
	uint32_t padded = pad4(len);
	msg_u32(m, len);
	memcpy(&m->payload[m->size - 8], s, len);
	m->size += padded;
}

static int send_msg(struct msg *m, int fd)
{
	struct iovec iov = { m, m->size };
	struct msghdr hdr;
	char cbuf[CMSG_SPACE(sizeof(int))];

	memset(&hdr, 0, sizeof(hdr));
	hdr.msg_iov = &iov;
	hdr.msg_iovlen = 1;

	if (fd >= 0) {
		struct cmsghdr *c;
		memset(cbuf, 0, sizeof(cbuf));
		hdr.msg_control = cbuf;
		hdr.msg_controllen = sizeof(cbuf);
		c = CMSG_FIRSTHDR(&hdr);
		c->cmsg_level = SOL_SOCKET;
		c->cmsg_type = SCM_RIGHTS;
		c->cmsg_len = CMSG_LEN(sizeof(int));
		memcpy(CMSG_DATA(c), &fd, sizeof(int));
	}

	return sendmsg(g_sock, &hdr, MSG_NOSIGNAL) < 0 ? -1 : 0;
}

static uint8_t s_rbuf[16384];
static size_t s_rlen = 0;

/* Reads one complete Wayland event from the stream (handles coalesced reads). */
static int recv_msg(struct msg *m, int *fd_out)
{
	if (fd_out) *fd_out = -1;

	for (;;) {
		if (s_rlen >= 8) {
			uint16_t size;
			memcpy(&size, s_rbuf + 6, 2);
			if (size >= 8 && s_rlen >= size) {
				size_t copy = size < sizeof(*m) ? size : sizeof(*m);
				memset(m, 0, sizeof(*m));
				memcpy(m, s_rbuf, copy);
				if (size > s_rlen) size = (uint16_t) s_rlen;
				memmove(s_rbuf, s_rbuf + size, s_rlen - size);
				s_rlen -= size;
				return 0;
			}
		}

		{
			struct iovec iov = { s_rbuf + s_rlen, sizeof(s_rbuf) - s_rlen };
			struct msghdr hdr;
			char cbuf[CMSG_SPACE(sizeof(int))];
			ssize_t n;

			memset(&hdr, 0, sizeof(hdr));
			hdr.msg_iov = &iov;
			hdr.msg_iovlen = 1;
			hdr.msg_control = cbuf;
			hdr.msg_controllen = sizeof(cbuf);

			n = recvmsg(g_sock, &hdr, 0);
			if (n <= 0) return -1;
			s_rlen += (size_t) n;

			if (fd_out) {
				struct cmsghdr *c;
				for (c = CMSG_FIRSTHDR(&hdr); c != NULL; c = CMSG_NXTHDR(&hdr, c)) {
					if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS) {
						memcpy(fd_out, CMSG_DATA(c), sizeof(int));
					}
				}
			}
		}
	}
}

int main(void)
{
	const char *runtime = getenv("XDG_RUNTIME_DIR");
	char path[256];
	struct sockaddr_un addr;
	int heapFd, dmabufFd;
	struct dma_heap_allocation_data alloc;
	void *pixels;
	struct msg m;
	uint32_t serial = 0;
	int got_configure = 0;
	int i;

	snprintf(path, sizeof(path), "%s/wayland-0",
		 runtime && *runtime ? runtime : "/tmp/runtime-alw");

	setvbuf(stdout, NULL, _IONBF, 0);
	setvbuf(stderr, NULL, _IONBF, 0);

	g_sock = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
	if (g_sock < 0) { perror("socket"); return 1; }
	memset(&addr, 0, sizeof(addr));
	addr.sun_family = AF_UNIX;
	strncpy(addr.sun_path, path, sizeof(addr.sun_path) - 1);
	if (connect(g_sock, (struct sockaddr *) &addr, sizeof(addr)) < 0) {
		perror("connect");
		return 1;
	}
	printf("connected to %s\n", path);

	/* 1. registry + globals */
	msg_init(&m, 1, 1); /* wl_display.get_registry(2) */
	msg_u32(&m, 2);
	send_msg(&m, -1);

	uint32_t compositor = 0, xdg_base = 0, dmabuf = 0;
	for (;;) {
		struct msg ev;
		fd_set rfds;
		struct timeval tv = { 5, 0 };
		if (s_rlen < 8) {
			FD_ZERO(&rfds);
			FD_SET(g_sock, &rfds);
			if (select(g_sock + 1, &rfds, NULL, NULL, &tv) <= 0) {
				fprintf(stderr, "registry wait timed out (compositor=%u xdg=%u dmabuf=%u)\n",
					compositor, xdg_base, dmabuf);
				break;
			}
		}
		if (recv_msg(&ev, NULL) < 0) break;
		if (ev.obj == 2 && ev.op == 0) {
			uint32_t name, ver, off = 0;
			char iface[64];
			uint32_t len;
			memcpy(&name, ev.payload + off, 4); off += 4;
			memcpy(&len, ev.payload + off, 4); off += 4;
			memcpy(iface, ev.payload + off, len); iface[len] = 0;
			off += pad4(len);
			memcpy(&ver, ev.payload + off, 4);
			fprintf(stderr, "global: %s v%u\n", iface, ver);
			if (!strcmp(iface, "wl_compositor")) {
				compositor = 3;
				msg_init(&m, 2, 0); msg_u32(&m, name);
				msg_str(&m, "wl_compositor"); msg_u32(&m, ver); msg_u32(&m, 3);
				send_msg(&m, -1);
			} else if (!strcmp(iface, "xdg_wm_base")) {
				xdg_base = 4;
				msg_init(&m, 2, 0); msg_u32(&m, name);
				msg_str(&m, "xdg_wm_base"); msg_u32(&m, ver); msg_u32(&m, 4);
				send_msg(&m, -1);
			} else if (!strcmp(iface, "zwp_linux_dmabuf_v1")) {
				dmabuf = 5;
				msg_init(&m, 2, 0); msg_u32(&m, name);
				msg_str(&m, "zwp_linux_dmabuf_v1"); msg_u32(&m, 4); msg_u32(&m, 5);
				send_msg(&m, -1);
			}
		}
		if (compositor && xdg_base && dmabuf) {
			/* drain the rest of the initial events with a sync roundtrip */
			msg_init(&m, 1, 0); msg_u32(&m, 99); /* wl_display.sync */
			send_msg(&m, -1);
			for (;;) {
				struct msg ev;
				if (recv_msg(&ev, NULL) < 0) break;
				if (ev.obj == 99) break;
			}
			break;
		}
	}
	if (!compositor || !xdg_base || !dmabuf) {
		fprintf(stderr, "missing globals (compositor=%u xdg=%u dmabuf=%u)\n",
			compositor, xdg_base, dmabuf);
		return 2;
	}
	printf("globals bound: wl_compositor, xdg_wm_base, zwp_linux_dmabuf_v1 (v4)\n");

	/* 2. surface + xdg toplevel */
	msg_init(&m, compositor, 0); msg_u32(&m, 6); send_msg(&m, -1); /* create_surface */
	msg_init(&m, xdg_base, 2); msg_u32(&m, 7); msg_u32(&m, 6); send_msg(&m, -1); /* get_xdg_surface */
	msg_init(&m, 7, 1); msg_u32(&m, 8); send_msg(&m, -1); /* get_toplevel */
	msg_init(&m, 8, 2); msg_str(&m, "alw-dmabuf-test"); send_msg(&m, -1); /* set_title */
	msg_init(&m, 8, 3); msg_str(&m, "alw-dmabuf-test"); send_msg(&m, -1); /* set_app_id */
	msg_init(&m, 6, 6); send_msg(&m, -1); /* commit (no buffer) -> configure */

	/* 3. wait for xdg_surface.configure, ack it */
	for (i = 0; i < 200 && !got_configure; i++) {
		struct msg ev;
		if (recv_msg(&ev, NULL) < 0) break;
		if (ev.obj == 7 && ev.op == 0) {
			memcpy(&serial, ev.payload, 4);
			got_configure = 1;
		}
	}
	if (!got_configure) {
		fprintf(stderr, "no configure received\n");
		return 3;
	}
	msg_init(&m, 7, 4); msg_u32(&m, serial); send_msg(&m, -1); /* ack_configure */
	printf("toplevel configured (serial=%u)\n", serial);

	/* 4. real DMA-BUF from the Android system heap */
	heapFd = open("/dev/dma_heap/system", O_RDONLY | O_CLOEXEC);
	if (heapFd < 0) { perror("open(/dev/dma_heap/system)"); return 4; }
	memset(&alloc, 0, sizeof(alloc));
	alloc.len = (uint64_t) STRIDE * H;
	alloc.fd_flags = O_RDWR | O_CLOEXEC;
	if (ioctl(heapFd, DMA_HEAP_IOCTL_ALLOC, &alloc) < 0) {
		perror("DMA_HEAP_IOCTL_ALLOC");
		return 4;
	}
	dmabufFd = (int) alloc.fd;
	printf("allocated DMA-BUF: fd=%d len=%llu\n", dmabufFd, (unsigned long long) alloc.len);

	pixels = mmap(NULL, alloc.len, PROT_READ | PROT_WRITE, MAP_SHARED, dmabufFd, 0);
	if (pixels == MAP_FAILED) { perror("mmap(dmabuf)"); return 5; }
	{
		uint32_t *p = pixels;
		int x, y;
		for (y = 0; y < H; y++) {
			for (x = 0; x < W; x++) {
				uint32_t r = (uint32_t) (x * 255 / W);
				uint32_t g = (uint32_t) (y * 255 / H);
				uint32_t b = 64 + (uint32_t) (((x ^ y) & 0x3f) * 3);
				p[y * W + x] = 0xFF000000u | (r << 16) | (g << 8) | b;
			}
		}
	}
	munmap(pixels, alloc.len);

	/* 5. zwp_linux_buffer_params_v1: add(fd) + create_immediate */
	msg_init(&m, dmabuf, 1); msg_u32(&m, 9); send_msg(&m, -1); /* create_params */
	msg_init(&m, 9, 1); /* add(fd, plane, offset, stride, mod_hi, mod_lo) */
	msg_u32(&m, 0); msg_u32(&m, 0); msg_u32(&m, STRIDE); msg_u32(&m, 0); msg_u32(&m, 0);
	send_msg(&m, dmabufFd);
	msg_init(&m, 9, 3); /* create_immediate(id, w, h, format, flags) */
	msg_u32(&m, 10); msg_u32(&m, W); msg_u32(&m, H);
	msg_u32(&m, DRM_FORMAT_ARGB8888); msg_u32(&m, 0);
	send_msg(&m, -1);
	printf("zwp_linux_dmabuf_v1 buffer submitted (%dx%d ARGB8888 linear)\n", W, H);

	/* 6. attach + commit -> bridge imports and composites */
	msg_init(&m, 6, 1); /* attach */
	msg_u32(&m, 10); msg_u32(&m, 0); msg_u32(&m, 0);
	send_msg(&m, -1);
	msg_init(&m, 6, 6); /* commit */
	send_msg(&m, -1);

	/* 7. pump events for a while so the bridge blits */
	for (i = 0; i < 300; i++) {
		fd_set rfds;
		struct timeval tv = { 0, 30000 };
		FD_ZERO(&rfds);
		FD_SET(g_sock, &rfds);
		if (select(g_sock + 1, &rfds, NULL, NULL, &tv) > 0) {
			struct msg ev;
			if (recv_msg(&ev, NULL) < 0) break;
		}
	}

	close(dmabufFd);
	close(heapFd);
	close(g_sock);
	printf("DMABUF_CLIENT_OK\n");
	return 0;
}
