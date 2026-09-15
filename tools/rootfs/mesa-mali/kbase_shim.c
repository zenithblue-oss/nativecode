#define _GNU_SOURCE
#include <stdio.h>
#include <stdint.h>
#include <stdarg.h>
#include <string.h>
#include <dlfcn.h>
#include <sys/ioctl.h>
#include <linux/types.h>

#define KBASE_IOCTL_TYPE 0x80
#define KBASE_IOCTL_GET_GPUPROPS \
    _IOW(KBASE_IOCTL_TYPE, 3, struct kbase_ioctl_get_gpuprops)

struct kbase_ioctl_get_gpuprops {
    __u64 buffer;
    __u32 size;
    __u32 flags;
};

static int (*real_ioctl)(int fd, unsigned long request, void *arg);

int ioctl(int fd, unsigned long request, ...)
{
    va_list args;
    va_start(args, request);
    void *arg = va_arg(args, void *);
    va_end(args);

    if (!real_ioctl)
        real_ioctl = dlsym(RTLD_NEXT, "ioctl");

    int ret = real_ioctl(fd, request, arg);
    if (request != KBASE_IOCTL_GET_GPUPROPS || ret < 0 || !arg)
        return ret;

    struct kbase_ioctl_get_gpuprops *p = arg;
    if (!p->buffer || p->size < 4)
        return ret;

    uint8_t *buf = (uint8_t *)(uintptr_t)p->buffer;
    for (size_t i = 0; i + 4 <= (size_t)p->size;) {
        uint32_t hdr;
        memcpy(&hdr, buf + i, 4);
        uint32_t key = hdr >> 2;
        uint32_t sz = 1u << (hdr & 3);
        i += 4;
        if (i + sz > (size_t)p->size)
            break;
        /* ponytail: raw 0xb8a31030 is arch11/prod3. Mesa pan_model has no
         * G615 row; panvk allow-list is 10/12/13 and fallback skips 11.
         * Passthrough -> INCOMPATIBLE_DRIVER. Spoof G610 v10 is required
         * for current panvk binary. Upgrade: rebuild panvk with v11 + G615
         * table row, then drop this rewrite. */
        if (key == 55 && sz >= 4) {
            uint32_t new_id = 0xa8671030;
            memcpy(buf + i, &new_id, 4);
        } else if (key == 1) {
            uint32_t new_prod = 0xa867;
            memcpy(buf + i, &new_prod, sz < 4 ? sz : 4);
        }
        i += sz;
    }
    return ret;
}
