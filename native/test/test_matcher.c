#include "../src/matcher.h"
#include "check.h"

#include <stdio.h>
#include <string.h>

/* run.sh runs from native/, so the shared fixtures are one level up. */
#define FIXTURES "../fixtures/ruletable/"

static size_t load(const char *name, uint8_t *buf, size_t cap) {
    char path[256];
    snprintf(path, sizeof path, FIXTURES "%s", name);
    FILE *f = fopen(path, "rb");
    CHECK(f != NULL);
    if (f == NULL) return 0;
    size_t n = fread(buf, 1, cap, f);
    int extra = fgetc(f);
    fclose(f);
    CHECK(extra == EOF);
    return n;
}

/* ---- events ---- */

static tc_event key(uint8_t dev, uint16_t code, int32_t value) {
    tc_event e;
    e.dev = dev;
    e.type = TC_EV_KEY;
    e.code = code;
    e.value = value;
    return e;
}

static tc_event axis(uint16_t code, int32_t value) {
    tc_event e;
    e.dev = TC_DEV_PAD;
    e.type = TC_EV_ABS;
    e.code = code;
    e.value = value;
    return e;
}

static tc_event tick_event(void) {
    tc_event e;
    memset(&e, 0, sizeof e);
    e.type = TC_EV_TICK;
    return e;
}

#define X_CODE      307   /* BTN_NORTH */
#define A_CODE      304   /* BTN_SOUTH */
#define AYN_DOWN    key(TC_DEV_GPIO, TC_CODE_AYN, 1)
#define AYN_UP      key(TC_DEV_GPIO, TC_CODE_AYN, 0)
#define AYN_REPEAT  key(TC_DEV_GPIO, TC_CODE_AYN, 2)
#define X_DOWN      key(TC_DEV_PAD, X_CODE, 1)
#define X_UP        key(TC_DEV_PAD, X_CODE, 0)
#define X_REPEAT    key(TC_DEV_PAD, X_CODE, 2)
#define A_DOWN      key(TC_DEV_PAD, A_CODE, 1)
#define A_UP        key(TC_DEV_PAD, A_CODE, 0)
#define HOME_DOWN   key(TC_DEV_PAD, TC_CODE_HOME, 1)
#define HOME_UP     key(TC_DEV_PAD, TC_CODE_HOME, 0)
#define SELECT_DOWN key(TC_DEV_PAD, TC_CODE_SELECT, 1)
#define SELECT_UP   key(TC_DEV_PAD, TC_CODE_SELECT, 0)

static int count_of(tc_out o, tc_kind kind) {
    int n = 0;
    for (uint8_t i = 0; i < o.n; i++) {
        if (o.items[i].kind == kind) n++;
    }
    return n;
}

static bool is(tc_decision d, tc_kind kind, uint16_t code, int32_t value) {
    return d.kind == kind && d.ev.code == code && d.ev.value == value;
}

/* ---- tables ---- */

/* The fixture, parsed by the real parser: generation 1, rule 0 = HOLD 400 · AYN · {X}. */
#define M1_HOLD_MS 400

static void m1(tc_state *s) {
    static tc_table t;
    static bool loaded = false;
    if (!loaded) {
        uint8_t buf[TC_TABLE_MAX_BYTES];
        size_t n = load("m1-swap.bin", buf, sizeof buf);
        CHECK(tc_table_parse(buf, n, 1, &t, NULL) == TC_TABLE_OK);
        loaded = true;
    }
    tc_init(s);
    CHECK(tc_matcher_set_table(s, &t) == TC_SET_INSTALLED);
}

/* ---- M1's eighteen, re-expressed ---- */

static void test_unbound_button_is_forwarded(void) {
    tc_state s;
    m1(&s);
    tc_out o = tc_step(&s, A_DOWN, 1000);
    CHECK(o.n == 1);
    CHECK(is(o.items[0], TC_FORWARD, A_CODE, 1));
    CHECK(o.deadline_ms == -1);
}

static void test_ayn_down_is_swallowed(void) {
    tc_state s;
    m1(&s);
    tc_out o = tc_step(&s, AYN_DOWN, 1000);
    CHECK(o.n == 1);
    CHECK(o.items[0].kind == TC_SWALLOW);
}

static void test_ayn_tap_synthesizes_the_press(void) {
    tc_state s;
    m1(&s);
    tc_step(&s, AYN_DOWN, 1000);
    tc_out o = tc_step(&s, AYN_UP, 1120);
    /* Nothing fired, so the app must still get its AYN press. */
    CHECK(o.n == 2);
    CHECK(is(o.items[0], TC_SYNTH, TC_CODE_AYN, 1));
    CHECK(is(o.items[1], TC_SYNTH, TC_CODE_AYN, 0));
}

static void test_ayn_plus_x_held_fires_the_rule(void) {
    tc_state s;
    m1(&s);
    tc_step(&s, AYN_DOWN, 1000);

    tc_out x = tc_step(&s, X_DOWN, 1050);
    CHECK(x.n == 1);
    CHECK(x.items[0].kind == TC_SWALLOW);
    CHECK(x.deadline_ms == 1050 + M1_HOLD_MS);

    tc_out fired = tc_step(&s, tick_event(), 1050 + M1_HOLD_MS);
    CHECK(fired.n == 1);
    CHECK(fired.items[0].kind == TC_FIRE);
    CHECK(fired.items[0].generation == 1);
    CHECK(fired.items[0].index == 0);
}

static void test_hold_does_not_fire_early(void) {
    tc_state s;
    m1(&s);
    tc_step(&s, AYN_DOWN, 1000);
    tc_step(&s, X_DOWN, 1050);
    tc_out early = tc_step(&s, tick_event(), 1050 + M1_HOLD_MS - 1);
    CHECK(count_of(early, TC_FIRE) == 0);
    CHECK(early.deadline_ms == 1050 + M1_HOLD_MS);
}

static void test_after_firing_nothing_reaches_the_app(void) {
    tc_state s;
    m1(&s);
    tc_step(&s, AYN_DOWN, 1000);
    tc_step(&s, X_DOWN, 1050);
    tc_step(&s, tick_event(), 1050 + M1_HOLD_MS);

    tc_out xu = tc_step(&s, X_UP, 1600);
    CHECK(xu.n == 1);
    CHECK(xu.items[0].kind == TC_SWALLOW);
    tc_out au = tc_step(&s, AYN_UP, 1700);
    CHECK(au.n == 1);
    CHECK(au.items[0].kind == TC_SWALLOW);
}

static void test_rule_fires_once_per_hold(void) {
    tc_state s;
    m1(&s);
    tc_step(&s, AYN_DOWN, 1000);
    tc_step(&s, X_DOWN, 1050);
    tc_step(&s, tick_event(), 1050 + M1_HOLD_MS);
    /* Later ticks while X stays down must not re-fire. */
    tc_out again = tc_step(&s, tick_event(), 1050 + M1_HOLD_MS + 500);
    CHECK(count_of(again, TC_FIRE) == 0);
}

static void test_short_tap_of_x_passes_through(void) {
    tc_state s;
    m1(&s);
    tc_step(&s, AYN_DOWN, 1000);
    tc_step(&s, X_DOWN, 1050);

    /* Released well before the hold completes: it was a tap, so the app gets the
       whole thing, modifier first, in order. SPEC: X's down is synthesised as part
       of the pass-through and its real up is FORWARDED (a down's fate decides its
       up's fate); M1 synthesised the up as well. */
    tc_out o = tc_step(&s, X_UP, 1150);
    CHECK(o.n == 3);
    CHECK(is(o.items[0], TC_SYNTH, TC_CODE_AYN, 1));
    CHECK(is(o.items[1], TC_SYNTH, X_CODE, 1));
    CHECK(is(o.items[2], TC_FORWARD, X_CODE, 0));

    /* AYN was already handed down, so its release is forwarded, not a pair. */
    tc_out au = tc_step(&s, AYN_UP, 1200);
    CHECK(au.n == 1);
    CHECK(is(au.items[0], TC_FORWARD, TC_CODE_AYN, 0));
}

static void test_x_alone_is_forwarded(void) {
    tc_state s;
    m1(&s);
    tc_out o = tc_step(&s, X_DOWN, 1000);
    CHECK(o.n == 1);
    CHECK(o.items[0].kind == TC_FORWARD);
    CHECK(o.deadline_ms == -1);
}

static void test_other_button_during_ayn_releases_the_modifier_first(void) {
    tc_state s;
    m1(&s);
    tc_step(&s, AYN_DOWN, 1000);
    /* A cannot complete the only rule, so no rule can still match: the app gets
       the modifier it never saw, then A. SPEC: both are synthesised by the
       pass-through, A as the last of the swallowed set; M1 forwarded A. */
    tc_out o = tc_step(&s, A_DOWN, 1050);
    CHECK(o.n == 2);
    CHECK(is(o.items[0], TC_SYNTH, TC_CODE_AYN, 1));
    CHECK(is(o.items[1], TC_SYNTH, A_CODE, 1));
    /* and A's up, like AYN's, is now the game's */
    tc_out au = tc_step(&s, A_UP, 1100);
    CHECK(au.n == 1);
    CHECK(is(au.items[0], TC_FORWARD, A_CODE, 0));
}

static void test_panic_fires_after_the_full_duration(void) {
    tc_state s;
    m1(&s);
    tc_step(&s, HOME_DOWN, 1000);
    tc_out armed = tc_step(&s, SELECT_DOWN, 1100);
    CHECK(count_of(armed, TC_PANIC) == 0);
    CHECK(armed.deadline_ms == 1100 + TC_PANIC_MS);

    tc_out early = tc_step(&s, tick_event(), 1100 + TC_PANIC_MS - 1);
    CHECK(count_of(early, TC_PANIC) == 0);

    tc_out boom = tc_step(&s, tick_event(), 1100 + TC_PANIC_MS);
    CHECK(count_of(boom, TC_PANIC) == 1);
}

static void test_panic_is_cancelled_by_releasing_a_key(void) {
    tc_state s;
    m1(&s);
    tc_step(&s, HOME_DOWN, 1000);
    tc_step(&s, SELECT_DOWN, 1100);
    tc_out released = tc_step(&s, SELECT_UP, 1200);
    CHECK(released.deadline_ms == -1);

    tc_out later = tc_step(&s, tick_event(), 1100 + TC_PANIC_MS + 1000);
    CHECK(count_of(later, TC_PANIC) == 0);
}

static void test_x_autorepeat_does_not_delay_hold(void) {
    tc_state s;
    m1(&s);
    tc_step(&s, AYN_DOWN, 1000);
    tc_out down = tc_step(&s, X_DOWN, 1050);
    CHECK(down.deadline_ms == 1050 + M1_HOLD_MS);

    tc_out r1 = tc_step(&s, X_REPEAT, 1083);
    CHECK(r1.n == 1);
    CHECK(is(r1.items[0], TC_SWALLOW, X_CODE, 2));
    CHECK(r1.deadline_ms == 1050 + M1_HOLD_MS);
    tc_out r2 = tc_step(&s, X_REPEAT, 1116);
    CHECK(r2.deadline_ms == 1050 + M1_HOLD_MS);
    tc_out r3 = tc_step(&s, X_REPEAT, 1300);
    CHECK(r3.deadline_ms == 1050 + M1_HOLD_MS);

    tc_out fired = tc_step(&s, tick_event(), 1050 + M1_HOLD_MS);
    CHECK(count_of(fired, TC_FIRE) == 1);
}

static void test_autorepeat_of_swallowed_key_is_swallowed(void) {
    tc_state s;
    m1(&s);
    tc_step(&s, AYN_DOWN, 1000);
    tc_step(&s, X_DOWN, 1050);
    tc_step(&s, tick_event(), 1050 + M1_HOLD_MS);  /* fired; X still held */
    tc_out o = tc_step(&s, X_REPEAT, 1050 + M1_HOLD_MS + 33);
    CHECK(o.n == 1);
    CHECK(is(o.items[0], TC_SWALLOW, X_CODE, 2));
}

static void test_autorepeat_of_forwarded_key_is_forwarded(void) {
    tc_state s;
    m1(&s);
    tc_step(&s, X_DOWN, 1000);  /* no session: forwarded */
    tc_out o = tc_step(&s, X_REPEAT, 1033);
    CHECK(o.n == 1);
    CHECK(is(o.items[0], TC_FORWARD, X_CODE, 2));
    CHECK(o.deadline_ms == -1);
}

static void test_ayn_autorepeat_is_swallowed_and_does_not_reset_session(void) {
    tc_state s;
    m1(&s);
    tc_step(&s, AYN_DOWN, 1000);
    tc_step(&s, X_DOWN, 1050);  /* arms the hold */
    tc_out r = tc_step(&s, AYN_REPEAT, 1100);
    CHECK(r.n == 1);
    CHECK(is(r.items[0], TC_SWALLOW, TC_CODE_AYN, 2));
    CHECK(r.deadline_ms == 1050 + M1_HOLD_MS);
    tc_out fired = tc_step(&s, tick_event(), 1050 + M1_HOLD_MS);
    CHECK(count_of(fired, TC_FIRE) == 1);
}

static void test_panic_is_not_shadowed_by_a_due_rule_in_the_same_step(void) {
    tc_state s;
    m1(&s);
    tc_step(&s, AYN_DOWN, 1000);
    tc_out x = tc_step(&s, X_DOWN, 1050);
    CHECK(count_of(x, TC_SWALLOW) == 1);  /* hold armed for 1450 */

    /* Home then Select while AYN is held. SPEC: Home cannot extend any rule, so the
       session passes through and the hold is DISARMED (the combo was abandoned);
       M1 kept it armed. Either way only panic may fire below. */
    tc_step(&s, HOME_DOWN, 1100);
    tc_out armed = tc_step(&s, SELECT_DOWN, 1150);  /* panic for 5150 */
    CHECK(count_of(armed, TC_PANIC) == 0);

    tc_out boom = tc_step(&s, tick_event(), 1150 + TC_PANIC_MS);
    CHECK(boom.n == 1);
    CHECK(count_of(boom, TC_PANIC) == 1);
    CHECK(count_of(boom, TC_FIRE) == 0);
}

static void test_unbound_axis_is_forwarded(void) {
    /* Nothing in this table binds the D-pad, so its edges are the game's, in a session
       or out of one. */
    tc_state s;
    m1(&s);
    CHECK(is(tc_step(&s, axis(16, -1), 1000).items[0], TC_FORWARD, 16, -1));
    CHECK(is(tc_step(&s, axis(16, 0), 1100).items[0], TC_FORWARD, 16, 0));
    /* and an UNBOUND axis is not a button at all: inside a session it is forwarded
       without disturbing the chord (a stick nudge must not abandon AYN + X) */
    tc_step(&s, AYN_DOWN, 2000);
    tc_out o = tc_step(&s, axis(16, 1), 2050);
    CHECK(o.n == 1);
    CHECK(is(o.items[0], TC_FORWARD, 16, 1));
    CHECK(count_of(tc_step(&s, AYN_UP, 2100), TC_SYNTH) == 2);   /* the chord was intact */
}

static void test_panic_keys_still_reach_the_app(void) {
    /* Home and Select are not modifiers of this table, so they belong to the app.
       The derived-modifier principle, in one test. */
    tc_state s;
    m1(&s);
    tc_out o = tc_step(&s, HOME_DOWN, 1000);
    CHECK(count_of(o, TC_FORWARD) == 1);
    tc_out u = tc_step(&s, HOME_UP, 1100);
    CHECK(count_of(u, TC_FORWARD) == 1);
}

int main(void) {
    printf("tc_matcher\n");
    RUN(test_unbound_button_is_forwarded);
    RUN(test_ayn_down_is_swallowed);
    RUN(test_ayn_tap_synthesizes_the_press);
    RUN(test_ayn_plus_x_held_fires_the_rule);
    RUN(test_hold_does_not_fire_early);
    RUN(test_after_firing_nothing_reaches_the_app);
    RUN(test_rule_fires_once_per_hold);
    RUN(test_short_tap_of_x_passes_through);
    RUN(test_x_alone_is_forwarded);
    RUN(test_other_button_during_ayn_releases_the_modifier_first);
    RUN(test_x_autorepeat_does_not_delay_hold);
    RUN(test_autorepeat_of_swallowed_key_is_swallowed);
    RUN(test_autorepeat_of_forwarded_key_is_forwarded);
    RUN(test_ayn_autorepeat_is_swallowed_and_does_not_reset_session);
    RUN(test_panic_is_not_shadowed_by_a_due_rule_in_the_same_step);
    RUN(test_panic_fires_after_the_full_duration);
    RUN(test_panic_is_cancelled_by_releasing_a_key);
    RUN(test_panic_keys_still_reach_the_app);
    RUN(test_unbound_axis_is_forwarded);

    printf("\n%d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
