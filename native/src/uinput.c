#include "uinput.h"

#include <fcntl.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

void tc_uinput_init(tc_uinput *u) {
    u->fd = -1;
    u->name[0] = '\0';
}

/* True if any bit up to and including [max] is set. */
static bool any_bit_set(const unsigned long *bits, int max) {
    for (int c = 0; c <= max; c++) {
        if (tc_has_bit(bits, c)) return true;
    }
    return false;
}

int tc_uinput_create(const tc_device *src, const char *name, tc_uinput *out) {
    tc_uinput_init(out);

    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -1;

    unsigned long keys[TC_NLONGS(KEY_MAX + 1)];
    unsigned long abs[TC_NLONGS(ABS_MAX + 1)];

    /* EVIOCGBIT SUCCEEDS with an all-zero bitmap for an event type the device
       does not support, so "the ioctl worked" is not the same as "the device has
       this capability". Require at least one bit to actually be set, or the twin
       advertises a capability its source lacks — gpio-keys reports EV=3 while a
       twin built on the weaker test reported EV=b with no axes at all. Harmless
       to Android's current classification, but the twin is supposed to MIRROR the
       source, and a device lying about what it can do is a bad foundation for a
       grab. */
    bool have_keys = tc_read_caps(src->fd, EV_KEY, keys, sizeof keys) >= 0 &&
                     any_bit_set(keys, KEY_MAX);
    bool have_abs = tc_read_caps(src->fd, EV_ABS, abs, sizeof abs) >= 0 &&
                    any_bit_set(abs, ABS_MAX);

    if (have_keys) {
        ioctl(fd, UI_SET_EVBIT, EV_KEY);
        for (int c = 0; c <= KEY_MAX; c++) {
            if (tc_has_bit(keys, c)) ioctl(fd, UI_SET_KEYBIT, c);
        }
    }
    if (have_abs) {
        ioctl(fd, UI_SET_EVBIT, EV_ABS);
        for (int c = 0; c <= ABS_MAX; c++) {
            if (tc_has_bit(abs, c)) ioctl(fd, UI_SET_ABSBIT, c);
        }
    }
    ioctl(fd, UI_SET_EVBIT, EV_SYN);

    struct uinput_setup setup;
    memset(&setup, 0, sizeof setup);
    /* Copy the source's identity: games keying off vendor/product must still
       recognise the controller. Checked, not best-effort — an unnoticed failure
       here leaves bus/vendor/product all zero and still reports success, which
       produces a twin no game recognises and gives no hint why. Better to fail
       loudly: the caller treats creation failure as fatal. */
    if (ioctl(src->fd, EVIOCGID, &setup.id) < 0) {
        close(fd);
        return -1;
    }
    snprintf(setup.name, sizeof setup.name, "%s", name);

    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0) {
        close(fd);
        return -1;
    }

    /* Axis ranges, after UI_DEV_SETUP and before UI_DEV_CREATE. Without these the
       sticks and triggers would report a default range and read as broken. */
    if (have_abs) {
        for (int c = 0; c <= ABS_MAX; c++) {
            if (!tc_has_bit(abs, c)) continue;
            struct input_absinfo info;
            if (ioctl(src->fd, EVIOCGABS(c), &info) < 0) continue;
            struct uinput_abs_setup as;
            memset(&as, 0, sizeof as);
            as.code = (uint16_t)c;
            as.absinfo = info;
            ioctl(fd, UI_ABS_SETUP, &as);
        }
    }

    if (ioctl(fd, UI_DEV_CREATE) < 0) {
        close(fd);
        return -1;
    }

    out->fd = fd;
    snprintf(out->name, sizeof out->name, "%s", name);
    return 0;
}

int tc_uinput_emit(tc_uinput *u, uint16_t type, uint16_t code, int32_t value) {
    if (u->fd < 0) return -1;
    struct input_event ev;
    memset(&ev, 0, sizeof ev);
    ev.type = type;
    ev.code = code;
    ev.value = value;
    return write(u->fd, &ev, sizeof ev) == (ssize_t)sizeof ev ? 0 : -1;
}

int tc_uinput_syn(tc_uinput *u) {
    return tc_uinput_emit(u, EV_SYN, SYN_REPORT, 0);
}

void tc_uinput_destroy(tc_uinput *u) {
    if (u->fd < 0) return;
    ioctl(u->fd, UI_DEV_DESTROY);
    close(u->fd);
    u->fd = -1;
}
