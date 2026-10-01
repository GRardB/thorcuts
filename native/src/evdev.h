#ifndef THORCUTS_EVDEV_H
#define THORCUTS_EVDEV_H

#include <linux/input.h>
#include <stdbool.h>
#include <stddef.h>

typedef struct {
    int  fd;
    bool grabbed;
    char name[80];
    char path[64];
} tc_device;

void tc_device_init(tc_device *d);

/* Open the AYN gamepad, matched BY CAPABILITY, not by name: AYN's layout switch
   re-creates it as "Odin Controller" or "Xbox Wireless Controller" with different
   product ids, so a name match breaks the moment the user toggles that setting.
   Returns 0 on success, -1 if not found. */
int tc_open_pad(tc_device *d);

/* Open the first device whose EVIOCGNAME matches [name] exactly. */
int tc_open_named(const char *name, tc_device *d);

/* Read up to [max] events. Returns the count, or -1 on error. */
int tc_read_events(tc_device *d, struct input_event *evs, size_t max);

/* Take/release exclusive delivery. Grabbing stops the kernel delivering these
   events to anyone else, Android included. */
int tc_grab(tc_device *d);
int tc_ungrab(tc_device *d);

void tc_close(tc_device *d);

#endif /* THORCUTS_EVDEV_H */
