#ifndef THORCUTS_MATCHER_H
#define THORCUTS_MATCHER_H

/*
 * The matcher: parent spec §6 over the installed rule table.
 *
 * Every event is classified (down / up / repeat / axis edge). A SESSION opens on the
 * first down of a modifier the table uses, and each event inside it is decided by
 * comparing the held modifiers and the swallowed buttons against the rules: a PRESS
 * rule fires on the down that completes it, a HOLD rule arms a deadline, a bare PRESS
 * fires on the modifier's release, and when no rule can match any more the whole
 * session is handed to the game (pass-through) so a modifier's normal job survives —
 * as it is when the modifiers are held alone past TC_LONE_MS with nothing armed, so
 * a long press reaches the system as a long press and not as a tap on the release.
 * A tap the game IS owed goes out with a width: its down on the release, its up
 * TC_TAP_MS later. The invariant every decision serves: a down's fate decides its
 * up's fate.
 *
 * Pure: no syscalls, no allocation, no time of its own. main.c feeds events and
 * TC_EV_TICKs and emits the decisions. Host-tested in test/test_matcher.c.
 *
 * The table is this matcher's INPUT, which is why this header includes ruletable.h
 * and not the other way round.
 */

#include "ruletable.h"

#include <stdbool.h>
#include <stdint.h>

/* Logical device slots. The helper grabs exactly these two: every modifier lives on
   one of them and every bindable button is on the pad. Equal to TC_RT_DEV_*. */
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

/* The panic combo's keys, used by track_panic only. The table-driven paths use
   tc_modifier_controls; test_ruletable.c pins these three to it. */
#define TC_CODE_AYN    194  /* KEY_F24,    on TC_DEV_GPIO */
#define TC_CODE_HOME   102  /* KEY_HOME,   on TC_DEV_PAD  */
#define TC_CODE_SELECT 314  /* BTN_SELECT, on TC_DEV_PAD  */

/* The panic combo: Home + Select held this long releases every device and exits.
   Deliberately NOT Home + Back 5s, which is another Thor app's panic combo — a
   user may have both installed. */
#define TC_PANIC_MS 4000

/* A session waiting on nothing but its modifiers — held, no button swallowed, no
   rule armed — is handed to the game after this long: every held modifier's down
   is synthesised and the session is the game's from then on. So a long AYN press
   reaches AYN's own long-press (hide the bottom screen) and a long Start reaches
   the game, instead of collapsing into a tap on the release; the price is that a
   combo must be completed within this window of the last modifier down. Erring
   long: a window too short loses combos AND hands the system a tap, a window too
   long only delays the system's long-press by that much. Tunable; not a setting.
   Design: docs/superpowers/specs/2026-10-18-m6-follow-ups-design.md §3. */
#define TC_LONE_MS 500

/* A synthesised tap has a width: its down goes out on the modifier's release and its
   up this much later, on a tick. A down and an up in one report are a press of no
   width — the kernel stamps them alike — and a game that samples the pad once a frame
   (every emulator) never sees it, though the system's own key handlers do. 50 ms
   clears a frame at 30 fps with room; the cost is only that the game's release lags
   the thumb's by this much. Tunable; not a setting. */
#define TC_TAP_MS 50

/* Bounds. A pass-through emits at most 5 modifier downs + TC_MAX_PRESSED button
   downs, plus the event itself and the tap's up a re-press flushes first: 16 holds
   the worst case, and test_matcher.c provokes it. */
#define TC_MAX_DECISIONS 16
#define TC_MAX_PRESSED   8
#define TC_MAX_AXES      (TC_MAX_RULES * TC_MAX_BUTTONS)

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
    TC_FIRE,         /* rule .index of the table at .generation matched; tell the app */
    TC_PANIC         /* release every device and exit */
} tc_kind;

typedef struct {
    tc_kind  kind;
    tc_event ev;          /* meaningful for TC_FORWARD and TC_SYNTH */
    uint32_t generation;  /* meaningful for TC_FIRE: the INSTALLED table's */
    uint16_t index;       /* meaningful for TC_FIRE: the rule's index in it */
} tc_decision;

typedef struct {
    tc_decision items[TC_MAX_DECISIONS];
    uint8_t     n;
    /* Absolute ms at which main.c should feed a TC_EV_TICK, or -1 for none. */
    int64_t     deadline_ms;
} tc_out;

/* A button whose DOWN was swallowed and which is still physically down. `synthed`
   means the game has since been handed that down (a pass-through), so its up must
   be forwarded rather than swallowed. */
typedef struct {
    tc_control ctl;
    bool       synthed;
} tc_pressed;

/* A bound axis control and whether its threshold is currently crossed. Tracked
   always; consulted only inside a session. */
typedef struct {
    tc_control ctl;
    bool       pressed;
} tc_axis;

typedef struct {
    /* The table. `table` is matched from; `pending` waits for the session to close. */
    tc_table   table;
    tc_table   pending;
    bool       has_pending;
    uint8_t    mods;              /* M: OR of every installed rule's modifiers */
    tc_axis    axes[TC_MAX_AXES]; /* every distinct ABS button in the table */
    int        naxes;

    /* The session. Open from the first modifier down until no modifier is held and
       no swallowed button is still down. */
    bool       session;
    uint8_t    held;              /* modifiers physically down, bit = TC_MOD_* */
    uint8_t    synthed;           /* modifiers whose down the game has been handed */
    tc_pressed pressed[TC_MAX_PRESSED];
    int        npressed;
    bool       fired;             /* at least one rule fired this session */
    bool       passed;            /* the session was handed to the game */
    int        armed;             /* rule index with a pending HOLD deadline, or -1 */
    int64_t    hold_deadline_ms;  /* -1 = none */
    int64_t    lone_deadline_ms;  /* TC_LONE_MS after the last modifier down with nothing
                                     else to wait for; -1 = none */

    /* Taps whose up is still owed, by TC_MOD_* index: the absolute ms at which the
       synthesised up goes out, or -1. Outlives the session that tapped; a real down
       of the same key flushes it first. */
    int64_t    tap_up_ms[TC_MOD_COUNT];

    /* The panic combo, tracked from raw events whatever the table says. */
    bool    home_held;
    bool    select_held;
    int64_t panic_deadline_ms;    /* -1 = none */
} tc_state;

typedef enum {
    TC_SET_INSTALLED = 0,  /* matching from it now */
    TC_SET_DEFERRED        /* a session is open; installed at its close */
} tc_set_result;

/* Start with an EMPTY table: nothing is a modifier, every event is forwarded. */
void tc_init(tc_state *s);

/* Install t, or defer it if a session is open. Copies; t need not outlive the call. */
tc_set_result tc_matcher_set_table(tc_state *s, const tc_table *t);

tc_out tc_step(tc_state *s, tc_event ev, int64_t now_ms);

#endif /* THORCUTS_MATCHER_H */
