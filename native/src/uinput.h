#ifndef THORCUTS_UINPUT_H
#define THORCUTS_UINPUT_H

#include "evdev.h"

#include <stdint.h>

typedef struct {
    int  fd;
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
int  tc_uinput_emit(tc_uinput *u, uint16_t type, uint16_t code, int32_t value);
int  tc_uinput_syn(tc_uinput *u);
void tc_uinput_destroy(tc_uinput *u);

#endif /* THORCUTS_UINPUT_H */
