#ifndef THORCUTS_MATCHER_H
#define THORCUTS_MATCHER_H

#include <stdbool.h>
#include <stdint.h>

/* Logical device slots. M1 grabs exactly these two, and no others: every
   modifier lives on one of them and every bindable button is on the pad. */
enum {
    TC_DEV_PAD   = 0,  /* the AYN gamepad: face, shoulders, sticks, D-pad, Home, Back */
    TC_DEV_GPIO  = 1,  /* "gpio-keys": the AYN button, and Volume + */
    TC_DEV_COUNT = 2
};

/* evdev event types. */
#define TC_EV_SYN 0x00
#define TC_EV_KEY 0x01
#define TC_EV_ABS 0x03

/* Synthetic type fed by main.c when a deadline is reached. Never from a device. */
#define TC_EV_TICK 0xF0

/* Control codes. These MUST match docs/superpowers/findings/…-m1-device-recon.md.
   If Task 1 measured something different, change these. */
#define TC_CODE_AYN    194  /* KEY_F24,    on TC_DEV_GPIO */
#define TC_CODE_X      307  /* BTN_NORTH,  on TC_DEV_PAD  */
#define TC_CODE_HOME   102  /* KEY_HOME,   on TC_DEV_PAD  */
#define TC_CODE_SELECT 314  /* BTN_SELECT, on TC_DEV_PAD  */

/* M1's single hardcoded rule: hold AYN + X. */
#define TC_RULE_SWAP_SCREENS 1
#define TC_HOLD_MS 400

/* The panic combo: Home + Select held this long releases every device and exits.
   Deliberately NOT Home + Back 5s, which is another Thor app's panic combo — a
   user may have both installed. */
#define TC_PANIC_MS 4000

#define TC_MAX_DECISIONS 8

typedef struct {
    uint8_t  dev;    /* TC_DEV_* */
    uint16_t type;   /* TC_EV_*  */
    uint16_t code;
    int32_t  value;  /* for TC_EV_KEY: 1 = press, 0 = release, 2 = autorepeat */
} tc_event;

typedef enum {
    TC_FORWARD = 0,  /* emit .ev on this device's virtual twin, unchanged */
    TC_SWALLOW,      /* emit nothing: the app under us never sees it */
    TC_SYNTH,        /* emit .ev: a press withheld earlier and released now */
    TC_FIRE,         /* .rule_id matched; tell the app */
    TC_PANIC         /* release every device and exit */
} tc_kind;

typedef struct {
    tc_kind  kind;
    tc_event ev;       /* meaningful for TC_FORWARD and TC_SYNTH */
    uint16_t rule_id;  /* meaningful for TC_FIRE */
} tc_decision;

typedef struct {
    tc_decision items[TC_MAX_DECISIONS];
    uint8_t     n;
    /* Absolute ms at which main.c should feed a TC_EV_TICK, or -1 for none. */
    int64_t     deadline_ms;
} tc_out;

typedef struct {
    bool    ayn_held;
    bool    ayn_emitted;       /* AYN-down already handed down: session passed through */
    bool    x_swallowed;       /* X is physically down, its down event withheld */
    bool    fired;             /* the rule fired during this AYN session */
    int64_t hold_deadline_ms;  /* -1 = none */

    bool    home_held;
    bool    select_held;
    int64_t panic_deadline_ms; /* -1 = none */
} tc_state;

void   tc_init(tc_state *s);
tc_out tc_step(tc_state *s, tc_event ev, int64_t now_ms);

#endif /* THORCUTS_MATCHER_H */
