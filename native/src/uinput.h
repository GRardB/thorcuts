#ifndef THORCUTS_UINPUT_H
#define THORCUTS_UINPUT_H

#include "evdev.h"

#include <stdint.h>

typedef struct {
    int  fd;
    bool live;   /* armed devices have fd >= 0 but do not exist yet */
    char name[80];
} tc_uinput;

void tc_uinput_init(tc_uinput *u);

/*
 * Create a virtual device mirroring [src]'s capabilities, named [name].
 *
 * One twin per real device, not one merged device: the one-to-one mapping keeps
 * controller-identity reasoning tractable, and gpio-keys carries Volume + as well
 * as the AYN button, so its twin must be able to reproduce Volume +.
 *
 * bus/vendor/product/version are copied from [src], because games that identify a
 * controller by those ids must keep recognising it.
 */
int  tc_uinput_create(const tc_device *src, const char *name, tc_uinput *out);

/*
 * Everything tc_uinput_create does EXCEPT the final UI_DEV_CREATE, which is split
 * out as tc_uinput_fire.
 *
 * The takeover needs the split: the clone's capabilities must be read while AYN's
 * pad still exists, but the device must not appear until AYN's pad has gone — that
 * is what lets it inherit controller number 1. An armed device holds an open
 * /dev/uinput fd and is fully configured; it simply does not exist yet.
 */
int  tc_uinput_arm(const tc_device *src, const char *name, tc_uinput *out);

/*
 * The single ioctl that makes an armed device exist.
 *
 * ON FAILURE THE FD IS STILL OPEN and the device is still armed: fire does not
 * close an fd it did not open. The caller MUST tc_uinput_destroy() on that path
 * or it leaks a /dev/uinput fd. This is the only function in this API whose
 * failure does not clean up after itself, which is why it is said out loud.
 */
int  tc_uinput_fire(tc_uinput *u);

int  tc_uinput_emit(tc_uinput *u, uint16_t type, uint16_t code, int32_t value);
int  tc_uinput_syn(tc_uinput *u);
void tc_uinput_destroy(tc_uinput *u);

#endif /* THORCUTS_UINPUT_H */
