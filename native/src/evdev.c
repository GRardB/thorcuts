#include "evdev.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

bool tc_has_bit(const unsigned long *bits, int code) {
    return (bits[code / (8 * sizeof(unsigned long))] >>
            (code % (8 * sizeof(unsigned long)))) & 1UL;
}

int tc_read_caps(int fd, int ev_type, unsigned long *bits, size_t nbytes) {
    memset(bits, 0, nbytes);
    return ioctl(fd, EVIOCGBIT(ev_type, nbytes), bits);
}

void tc_device_init(tc_device *d) {
    d->fd = -1;
    d->grabbed = false;
    d->name[0] = '\0';
    d->path[0] = '\0';
}

/* The gamepad, identified by what it can DO: a south face button and a hat axis.
   That combination is unique to the pad among the Thor's input devices, and it
   survives AYN's layout switch. */
static bool looks_like_pad(int fd) {
    unsigned long keys[TC_NLONGS(KEY_MAX + 1)];
    unsigned long abs[TC_NLONGS(ABS_MAX + 1)];
    if (tc_read_caps(fd, EV_KEY, keys, sizeof keys) < 0) return false;
    if (tc_read_caps(fd, EV_ABS, abs, sizeof abs) < 0) return false;
    return tc_has_bit(keys, BTN_SOUTH) && tc_has_bit(abs, ABS_HAT0X);
}

typedef bool (*matcher_fn)(int fd, const char *name, const void *arg);

static bool match_pad(int fd, const char *name, const void *arg) {
    (void)name;
    (void)arg;
    return looks_like_pad(fd);
}

static bool match_name(int fd, const char *name, const void *arg) {
    (void)fd;
    return strcmp(name, (const char *)arg) == 0;
}

static int scan_except(matcher_fn match, const void *arg, const char *exclude, tc_device *d) {
    DIR *dir = opendir("/dev/input");
    if (!dir) return -1;

    int found = -1;
    struct dirent *e;
    while ((e = readdir(dir)) != NULL) {
        if (strncmp(e->d_name, "event", 5) != 0) continue;

        char path[64];
        snprintf(path, sizeof path, "/dev/input/%s", e->d_name);
        if (exclude && strcmp(path, exclude) == 0) continue;

        int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue;

        char name[80] = {0};
        if (ioctl(fd, EVIOCGNAME(sizeof name - 1), name) < 0) name[0] = '\0';

        if (match(fd, name, arg)) {
            tc_device_init(d);
            d->fd = fd;
            snprintf(d->name, sizeof d->name, "%s", name);
            snprintf(d->path, sizeof d->path, "%s", path);
            found = 0;
            break;
        }
        close(fd);
    }
    closedir(dir);
    return found;
}

static int scan(matcher_fn match, const void *arg, tc_device *d) {
    return scan_except(match, arg, NULL, d);
}

int tc_open_pad(tc_device *d) {
    return scan_except(match_pad, NULL, NULL, d);
}

int tc_open_pad_except(const char *exclude_path, tc_device *d) {
    return scan_except(match_pad, NULL, exclude_path, d);
}

int tc_open_named(const char *name, tc_device *d) {
    return scan(match_name, name, d);
}

int tc_read_events(tc_device *d, struct input_event *evs, size_t max) {
    if (d->fd < 0) return -1;
    ssize_t got = read(d->fd, evs, max * sizeof *evs);
    if (got < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) return 0;
        return -1;
    }
    return (int)(got / (ssize_t)sizeof *evs);
}

int tc_grab(tc_device *d) {
    if (d->fd < 0) return -1;
    if (ioctl(d->fd, EVIOCGRAB, 1) < 0) return -1;
    d->grabbed = true;
    return 0;
}

int tc_ungrab(tc_device *d) {
    if (d->fd < 0 || !d->grabbed) return 0;
    /* Best effort by design: this runs on every exit path, including signals. */
    ioctl(d->fd, EVIOCGRAB, 0);
    d->grabbed = false;
    return 0;
}

void tc_close(tc_device *d) {
    if (d->fd < 0) return;
    tc_ungrab(d);
    close(d->fd);
    d->fd = -1;
}
