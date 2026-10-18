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

/* ---- in-test tables, for the semantics the fixtures do not cover ---- */

static tc_table T;

static void table_begin(uint32_t generation) {
    memset(&T, 0, sizeof T);
    T.generation = generation;
}

static tc_control K(uint16_t code) {
    tc_control c = { TC_DEV_PAD, TC_RT_EV_KEY, code, 1 };
    return c;
}

static tc_control AB(uint16_t code, int16_t value) {
    tc_control c = { TC_DEV_PAD, TC_RT_EV_ABS, code, value };
    return c;
}

/* Appends a rule; returns its index. The engine does not need canonical button order. */
static int rule(uint8_t mods, uint8_t style, uint16_t hold_ms, const tc_control *b, int n) {
    tc_rule *r = &T.rules[T.count];
    r->modifiers = mods;
    r->style = style;
    r->hold_ms = hold_ms;
    r->button_count = (uint8_t)n;
    for (int i = 0; i < n; i++) r->buttons[i] = b[i];
    return T.count++;
}

/* A fresh matcher with T installed. */
static void fresh(tc_state *s) {
    tc_init(s);
    CHECK(tc_matcher_set_table(s, &T) == TC_SET_INSTALLED);
}

#define M_AYN    ((uint8_t)(1u << TC_MOD_AYN))
#define M_HOME   ((uint8_t)(1u << TC_MOD_HOME))
#define M_BACK   ((uint8_t)(1u << TC_MOD_BACK))
#define M_START  ((uint8_t)(1u << TC_MOD_START))
#define M_SELECT ((uint8_t)(1u << TC_MOD_SELECT))
#define PRESS    TC_STYLE_PRESS
#define HOLD     TC_STYLE_HOLD

#define Y_CODE   308   /* BTN_WEST */
#define B_CODE   305   /* BTN_EAST */
#define L1_CODE  310   /* BTN_TL */
#define R1_CODE  311   /* BTN_TR */
#define VOLUP    115   /* KEY_VOLUMEUP, on gpio-keys */
#define Y_DOWN      key(TC_DEV_PAD, Y_CODE, 1)
#define Y_UP        key(TC_DEV_PAD, Y_CODE, 0)
#define B_DOWN      key(TC_DEV_PAD, B_CODE, 1)
#define L1_DOWN     key(TC_DEV_PAD, L1_CODE, 1)
#define R1_DOWN     key(TC_DEV_PAD, R1_CODE, 1)
#define VOLUP_DOWN  key(TC_DEV_GPIO, VOLUP, 1)
#define START_DOWN  key(TC_DEV_PAD, 315, 1)
#define START_UP    key(TC_DEV_PAD, 315, 0)
#define BACK_DOWN   key(TC_DEV_PAD, 158, 1)

static bool fires(tc_out o, uint32_t generation, uint16_t index) {
    for (uint8_t i = 0; i < o.n; i++) {
        if (o.items[i].kind == TC_FIRE && o.items[i].generation == generation &&
            o.items[i].index == index) {
            return true;
        }
    }
    return false;
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

/* ---- the decision table ---- */

static void test_press_rule_fires_on_the_completing_down(void) {
    table_begin(5);
    tc_control b[] = { K(X_CODE) };
    rule(M_AYN, PRESS, 0, b, 1);
    tc_state s;
    fresh(&s);
    tc_out a = tc_step(&s, AYN_DOWN, 1000);
    CHECK(a.n == 1 && a.items[0].kind == TC_SWALLOW);
    tc_out x = tc_step(&s, X_DOWN, 1050);
    CHECK(x.n == 2);
    CHECK(fires(x, 5, 0));
    CHECK(count_of(x, TC_SWALLOW) == 1);
    CHECK(x.deadline_ms == -1);
    CHECK(count_of(tc_step(&s, X_UP, 1100), TC_SWALLOW) == 1);
    CHECK(count_of(tc_step(&s, AYN_UP, 1150), TC_SWALLOW) == 1);
}

static void test_a_modifier_no_rule_uses_is_an_ordinary_button(void) {
    table_begin(1);
    tc_control b[] = { K(X_CODE) };
    rule(M_AYN, PRESS, 0, b, 1);
    tc_state s;
    fresh(&s);
    /* Home is in the fixed five but not in this table's M: forwarded, in any app. */
    CHECK(is(tc_step(&s, HOME_DOWN, 1000).items[0], TC_FORWARD, TC_CODE_HOME, 1));
    CHECK(is(tc_step(&s, HOME_UP, 1100).items[0], TC_FORWARD, TC_CODE_HOME, 0));
    /* and inside an AYN session it is just a button no rule wants: pass-through */
    tc_step(&s, AYN_DOWN, 2000);
    tc_out o = tc_step(&s, HOME_DOWN, 2050);
    CHECK(o.n == 2);
    CHECK(is(o.items[0], TC_SYNTH, TC_CODE_AYN, 1));
    CHECK(is(o.items[1], TC_SYNTH, TC_CODE_HOME, 1));
}

static void test_exact_modifier_set_distinguishes_chords(void) {
    table_begin(1);
    tc_control b[] = { K(X_CODE) };
    int both = rule(M_AYN | M_HOME, PRESS, 0, b, 1);
    int ayn = rule(M_AYN, PRESS, 0, b, 1);
    tc_state s;
    fresh(&s);
    tc_step(&s, AYN_DOWN, 1000);
    CHECK(count_of(tc_step(&s, HOME_DOWN, 1050), TC_SWALLOW) == 1);
    CHECK(fires(tc_step(&s, X_DOWN, 1100), 1, (uint16_t)both));
    tc_state s2;
    fresh(&s2);
    tc_step(&s2, AYN_DOWN, 1000);
    CHECK(fires(tc_step(&s2, X_DOWN, 1050), 1, (uint16_t)ayn));
}

static void test_exact_set_not_subset_a_superset_hold_passes_through(void) {
    /* The headline decision: a rule matches only when `held` EQUALS its modifier set, not when
       it is a subset. AYN+HOME is held (the AYN+HOME+{Y} rule puts HOME in M, so HOME is tracked
       as a modifier); only an AYN+{X} rule could want X. Under EXACT matching nothing matches
       {AYN,HOME}+{X}, so pressing X passes the whole gesture through. A SUBSET matcher would
       instead FIRE AYN+{X}, because {AYN} is a subset of {AYN,HOME}. */
    table_begin(1);
    tc_control bx[] = { K(X_CODE) };
    tc_control by[] = { K(Y_CODE) };
    rule(M_AYN, PRESS, 0, bx, 1);
    rule(M_AYN | M_HOME, PRESS, 0, by, 1);
    tc_state s;
    fresh(&s);
    CHECK(count_of(tc_step(&s, AYN_DOWN, 1000), TC_SWALLOW) == 1);
    CHECK(count_of(tc_step(&s, HOME_DOWN, 1050), TC_SWALLOW) == 1);
    tc_out o = tc_step(&s, X_DOWN, 1100);
    CHECK(o.n == 3);
    CHECK(is(o.items[0], TC_SYNTH, TC_CODE_AYN, 1));
    CHECK(is(o.items[1], TC_SYNTH, TC_CODE_HOME, 1));
    CHECK(is(o.items[2], TC_SYNTH, X_CODE, 1));
    CHECK(count_of(o, TC_FIRE) == 0);
}

static void test_modifiers_must_all_be_down_before_the_first_button(void) {
    /* Exact-set matching: with only AYN held, an AYN+HOME rule is not a candidate,
       so a button pressed before HOME passes the session through. */
    table_begin(1);
    tc_control b[] = { K(X_CODE) };
    rule(M_AYN | M_HOME, PRESS, 0, b, 1);
    tc_state s;
    fresh(&s);
    tc_step(&s, AYN_DOWN, 1000);
    tc_out o = tc_step(&s, X_DOWN, 1050);
    CHECK(o.n == 2);
    CHECK(is(o.items[0], TC_SYNTH, TC_CODE_AYN, 1));
    CHECK(is(o.items[1], TC_SYNTH, X_CODE, 1));
    CHECK(is(tc_step(&s, HOME_DOWN, 1100).items[0], TC_FORWARD, TC_CODE_HOME, 1));
}

static void test_multi_button_press_waits_for_the_last_button(void) {
    table_begin(1);
    tc_control b[] = { K(X_CODE), K(Y_CODE) };
    rule(M_AYN, PRESS, 0, b, 2);
    tc_state s;
    fresh(&s);
    tc_step(&s, AYN_DOWN, 1000);
    tc_out x = tc_step(&s, X_DOWN, 1050);
    CHECK(x.n == 1 && x.items[0].kind == TC_SWALLOW);   /* a prefix: wait */
    CHECK(x.deadline_ms == -1);
    tc_out y = tc_step(&s, Y_DOWN, 1100);
    CHECK(fires(y, 1, 0));
    CHECK(count_of(tc_step(&s, Y_UP, 1150), TC_SWALLOW) == 1);
    CHECK(count_of(tc_step(&s, X_UP, 1200), TC_SWALLOW) == 1);
    CHECK(count_of(tc_step(&s, AYN_UP, 1250), TC_SWALLOW) == 1);
}

static void test_abandoned_prefix_passes_through_as_a_tap(void) {
    table_begin(1);
    tc_control b[] = { K(X_CODE), K(Y_CODE) };
    rule(M_AYN, PRESS, 0, b, 2);
    tc_state s;
    fresh(&s);
    tc_step(&s, AYN_DOWN, 1000);
    tc_step(&s, X_DOWN, 1050);
    tc_out o = tc_step(&s, X_UP, 1100);
    CHECK(o.n == 3);
    CHECK(is(o.items[0], TC_SYNTH, TC_CODE_AYN, 1));
    CHECK(is(o.items[1], TC_SYNTH, X_CODE, 1));
    CHECK(is(o.items[2], TC_FORWARD, X_CODE, 0));
    CHECK(is(tc_step(&s, AYN_UP, 1150).items[0], TC_FORWARD, TC_CODE_AYN, 0));
}

static void test_bare_press_fires_on_release_and_not_on_down(void) {
    table_begin(1);
    tc_control b[] = { K(X_CODE) };
    int bare = rule(M_AYN, PRESS, 0, NULL, 0);
    int hold = rule(M_AYN, HOLD, 400, b, 1);
    tc_state s;
    fresh(&s);
    tc_out d = tc_step(&s, AYN_DOWN, 1000);
    CHECK(count_of(d, TC_FIRE) == 0);
    tc_out u = tc_step(&s, AYN_UP, 1100);
    CHECK(fires(u, 1, (uint16_t)bare));
    CHECK(count_of(u, TC_SWALLOW) == 1);
    CHECK(count_of(u, TC_SYNTH) == 0);
    /* with a button the bare rule is not what fires */
    tc_state s2;
    fresh(&s2);
    tc_step(&s2, AYN_DOWN, 1000);
    tc_step(&s2, X_DOWN, 1050);
    CHECK(fires(tc_step(&s2, tick_event(), 1450), 1, (uint16_t)hold));
    tc_out u2 = tc_step(&s2, AYN_UP, 1500);
    CHECK(count_of(u2, TC_FIRE) == 0);
    CHECK(count_of(u2, TC_SWALLOW) == 1);
}

static void test_bare_hold_arms_on_down_and_a_tap_synthesises(void) {
    table_begin(1);
    rule(M_HOME, HOLD, 500, NULL, 0);
    tc_state s;
    fresh(&s);
    tc_out d = tc_step(&s, HOME_DOWN, 1000);
    CHECK(d.n == 1 && d.items[0].kind == TC_SWALLOW);
    CHECK(d.deadline_ms == 1500);
    CHECK(count_of(tc_step(&s, tick_event(), 1499), TC_FIRE) == 0);
    CHECK(fires(tc_step(&s, tick_event(), 1500), 1, 0));
    CHECK(count_of(tc_step(&s, HOME_UP, 1600), TC_SWALLOW) == 1);
    /* released early: Home's normal job, deferred to release */
    tc_state s2;
    fresh(&s2);
    tc_step(&s2, HOME_DOWN, 1000);
    tc_out tap = tc_step(&s2, HOME_UP, 1200);
    CHECK(tap.n == 2);
    CHECK(is(tap.items[0], TC_SYNTH, TC_CODE_HOME, 1));
    CHECK(is(tap.items[1], TC_SYNTH, TC_CODE_HOME, 0));
    CHECK(tap.deadline_ms == -1);
}

static void test_bare_press_and_bare_hold_coexist(void) {
    table_begin(1);
    int tap = rule(M_AYN, PRESS, 0, NULL, 0);
    int hold = rule(M_AYN, HOLD, 600, NULL, 0);
    tc_state s;
    fresh(&s);
    CHECK(tc_step(&s, AYN_DOWN, 1000).deadline_ms == 1600);
    CHECK(fires(tc_step(&s, AYN_UP, 1200), 1, (uint16_t)tap));
    tc_state s2;
    fresh(&s2);
    tc_step(&s2, AYN_DOWN, 1000);
    CHECK(fires(tc_step(&s2, tick_event(), 1600), 1, (uint16_t)hold));
    CHECK(count_of(tc_step(&s2, AYN_UP, 1700), TC_FIRE) == 0);
}

static void test_fire_once_per_rule_not_per_session(void) {
    table_begin(1);
    tc_control bx[] = { K(X_CODE) };
    tc_control by[] = { K(Y_CODE) };
    int rx = rule(M_AYN, PRESS, 0, bx, 1);
    int ry = rule(M_AYN, PRESS, 0, by, 1);
    tc_state s;
    fresh(&s);
    tc_step(&s, AYN_DOWN, 1000);
    CHECK(fires(tc_step(&s, X_DOWN, 1050), 1, (uint16_t)rx));
    CHECK(count_of(tc_step(&s, X_UP, 1100), TC_SWALLOW) == 1);
    CHECK(fires(tc_step(&s, Y_DOWN, 1150), 1, (uint16_t)ry));
    /* after a fire, a button no rule wants is the game's alone: no modifier synth */
    tc_out a = tc_step(&s, A_DOWN, 1200);
    CHECK(a.n == 1 && is(a.items[0], TC_FORWARD, A_CODE, 1));
    CHECK(is(tc_step(&s, A_UP, 1250).items[0], TC_FORWARD, A_CODE, 0));
    CHECK(count_of(tc_step(&s, Y_UP, 1300), TC_SWALLOW) == 1);
    CHECK(count_of(tc_step(&s, AYN_UP, 1350), TC_SWALLOW) == 1);
}

static void test_lowest_index_wins_and_press_beats_hold(void) {
    table_begin(1);
    tc_control b[] = { K(X_CODE) };
    rule(M_AYN, HOLD, 400, b, 1);
    int first = rule(M_AYN, PRESS, 0, b, 1);
    rule(M_AYN, PRESS, 0, b, 1);
    tc_state s;
    fresh(&s);
    tc_step(&s, AYN_DOWN, 1000);
    tc_out x = tc_step(&s, X_DOWN, 1050);
    CHECK(count_of(x, TC_FIRE) == 1);
    CHECK(fires(x, 1, (uint16_t)first));
    CHECK(x.deadline_ms == -1);
}

/* ---- axes ---- */

static void test_dpad_axis_is_a_button(void) {
    table_begin(1);
    tc_control b[] = { AB(16, -1) };
    rule(M_AYN, PRESS, 0, b, 1);
    tc_state s;
    fresh(&s);
    /* outside a session the D-pad is the game's, edges and all */
    CHECK(is(tc_step(&s, axis(16, -1), 500).items[0], TC_FORWARD, 16, -1));
    CHECK(is(tc_step(&s, axis(16, 0), 600).items[0], TC_FORWARD, 16, 0));
    tc_step(&s, AYN_DOWN, 1000);
    tc_out left = tc_step(&s, axis(16, -1), 1050);
    CHECK(fires(left, 1, 0));
    CHECK(count_of(left, TC_SWALLOW) == 1);
    CHECK(count_of(tc_step(&s, axis(16, 0), 1100), TC_SWALLOW) == 1);   /* its release */
    CHECK(count_of(tc_step(&s, AYN_UP, 1150), TC_SWALLOW) == 1);
}

static void test_analog_threshold_with_hysteresis(void) {
    table_begin(1);
    tc_control b[] = { AB(9, 16384) };   /* ABS_GAS at half travel */
    rule(M_AYN, PRESS, 0, b, 1);
    tc_state s;
    fresh(&s);
    /* no session: every reading is forwarded, but the edge state is tracked */
    CHECK(is(tc_step(&s, axis(9, 16383), 100).items[0], TC_FORWARD, 9, 16383));
    CHECK(is(tc_step(&s, axis(9, 16384), 200).items[0], TC_FORWARD, 9, 16384));
    CHECK(s.axes[0].pressed);
    CHECK(is(tc_step(&s, axis(9, 12288), 300).items[0], TC_FORWARD, 9, 12288));
    CHECK(s.axes[0].pressed);                       /* 4*12288 == 3*16384: still pressed */
    CHECK(is(tc_step(&s, axis(9, 12287), 400).items[0], TC_FORWARD, 9, 12287));
    CHECK(!s.axes[0].pressed);
    /* in a session: the crossing fires, the ramp above it is swallowed, the release
       is swallowed (the game never saw the press), and the rest is forwarded */
    tc_step(&s, AYN_DOWN, 1000);
    tc_out on = tc_step(&s, axis(9, 16384), 1050);
    CHECK(fires(on, 1, 0));
    CHECK(count_of(on, TC_SWALLOW) == 1);
    CHECK(count_of(tc_step(&s, axis(9, 20000), 1060), TC_SWALLOW) == 1);
    CHECK(count_of(tc_step(&s, axis(9, 12287), 1070), TC_SWALLOW) == 1);
    CHECK(is(tc_step(&s, axis(9, 5000), 1080).items[0], TC_FORWARD, 9, 5000));
    CHECK(count_of(tc_step(&s, AYN_UP, 1100), TC_SWALLOW) == 1);
}

static void test_hat_flip_releases_one_control_and_presses_the_other(void) {
    table_begin(1);
    tc_control l[] = { AB(16, -1) };
    tc_control r[] = { AB(16, 1) };
    int left = rule(M_AYN, PRESS, 0, l, 1);
    int right = rule(M_AYN, PRESS, 0, r, 1);
    tc_state s;
    fresh(&s);
    tc_step(&s, AYN_DOWN, 1000);
    CHECK(fires(tc_step(&s, axis(16, -1), 1050), 1, (uint16_t)left));
    /* one reading: left released (bookkept silently), right pressed and fired */
    tc_out flip = tc_step(&s, axis(16, 1), 1100);
    CHECK(flip.n == 2);
    CHECK(fires(flip, 1, (uint16_t)right));
    CHECK(count_of(flip, TC_SWALLOW) == 1);
    CHECK(count_of(tc_step(&s, axis(16, 0), 1150), TC_SWALLOW) == 1);
}

static void test_an_abandoned_axis_press_is_synthesised_at_its_threshold(void) {
    table_begin(1);
    tc_control b[] = { AB(16, -1), K(X_CODE) };
    rule(M_AYN, PRESS, 0, b, 2);
    tc_state s;
    fresh(&s);
    tc_step(&s, AYN_DOWN, 1000);
    CHECK(count_of(tc_step(&s, axis(16, -1), 1050), TC_SWALLOW) == 1);   /* a prefix */
    tc_out o = tc_step(&s, axis(16, 0), 1100);
    CHECK(o.n == 3);
    CHECK(is(o.items[0], TC_SYNTH, TC_CODE_AYN, 1));
    CHECK(o.items[1].kind == TC_SYNTH && o.items[1].ev.type == TC_EV_ABS);
    CHECK(o.items[1].ev.code == 16 && o.items[1].ev.value == -1);
    CHECK(is(o.items[2], TC_FORWARD, 16, 0));
}

/* ---- the swap, and the generations ---- */

static void test_table_swap_is_deferred_until_the_session_closes(void) {
    table_begin(1);
    tc_control bx[] = { K(X_CODE) };
    rule(M_AYN, PRESS, 0, bx, 1);
    tc_state s;
    fresh(&s);
    tc_step(&s, AYN_DOWN, 1000);

    table_begin(2);
    tc_control by[] = { K(Y_CODE) };
    rule(M_AYN, PRESS, 0, by, 1);
    CHECK(tc_matcher_set_table(&s, &T) == TC_SET_DEFERRED);
    /* a second push while deferred replaces the pending copy */
    table_begin(3);
    rule(M_AYN, PRESS, 0, by, 1);
    CHECK(tc_matcher_set_table(&s, &T) == TC_SET_DEFERRED);

    /* the open session still fires from generation 1 */
    CHECK(fires(tc_step(&s, X_DOWN, 1050), 1, 0));
    tc_step(&s, X_UP, 1100);
    tc_step(&s, AYN_UP, 1150);   /* closes; generation 3 is installed now */
    CHECK(s.table.generation == 3);
    tc_step(&s, AYN_DOWN, 2000);
    CHECK(fires(tc_step(&s, Y_DOWN, 2050), 3, 0));
    tc_out x = tc_step(&s, X_DOWN, 2100);     /* no rule wants X any more */
    CHECK(x.n == 1 && is(x.items[0], TC_FORWARD, X_CODE, 1));
}

static void test_until_a_table_is_installed_everything_is_forwarded(void) {
    tc_state s;
    tc_init(&s);
    CHECK(is(tc_step(&s, AYN_DOWN, 1000).items[0], TC_FORWARD, TC_CODE_AYN, 1));
    CHECK(is(tc_step(&s, X_DOWN, 1050).items[0], TC_FORWARD, X_CODE, 1));
    CHECK(is(tc_step(&s, X_UP, 1100).items[0], TC_FORWARD, X_CODE, 0));
    tc_out u = tc_step(&s, AYN_UP, 1150);
    CHECK(is(u.items[0], TC_FORWARD, TC_CODE_AYN, 0));
    CHECK(u.deadline_ms == -1);
}

/* ---- the invariants ---- */

static void test_outside_a_session_every_event_is_forwarded_verbatim(void) {
    table_begin(1);
    tc_control b[] = { K(X_CODE), AB(16, -1) };
    rule(M_AYN, PRESS, 0, b, 2);
    tc_state s;
    fresh(&s);
    tc_event evs[] = { A_DOWN, X_DOWN, X_UP, axis(16, -1), axis(16, 0), axis(9, 30000),
                       key(TC_DEV_GPIO, VOLUP, 1), HOME_DOWN, HOME_UP };
    for (size_t i = 0; i < sizeof evs / sizeof evs[0]; i++) {
        tc_out o = tc_step(&s, evs[i], 1000 + (int64_t)i * 10);
        CHECK(o.n == 1);
        CHECK(o.items[0].kind == TC_FORWARD);
        CHECK(memcmp(&o.items[0].ev, &evs[i], sizeof evs[i]) == 0);
    }
}

static void test_panic_works_while_both_its_keys_are_modifiers(void) {
    table_begin(1);
    tc_control b[] = { K(X_CODE) };
    rule(M_HOME | M_SELECT, PRESS, 0, b, 1);
    tc_state s;
    fresh(&s);
    CHECK(count_of(tc_step(&s, HOME_DOWN, 1000), TC_SWALLOW) == 1);
    tc_out armed = tc_step(&s, SELECT_DOWN, 1100);
    CHECK(count_of(armed, TC_SWALLOW) == 1);
    /* The lone-modifier window is the sooner deadline: at its close both keys go to
       the system, live — as they always did when they were NOT modifiers of the table
       (test_panic_keys_still_reach_the_app) — and the panic, tracked from the raw
       events, is untouched by that and fires on time. */
    CHECK(armed.deadline_ms == 1100 + TC_LONE_MS);
    tc_out handed = tc_step(&s, tick_event(), 1100 + TC_LONE_MS);
    CHECK(handed.n == 2);
    CHECK(is(handed.items[0], TC_SYNTH, TC_CODE_HOME, 1));
    CHECK(is(handed.items[1], TC_SYNTH, TC_CODE_SELECT, 1));
    CHECK(handed.deadline_ms == 1100 + TC_PANIC_MS);
    tc_out boom = tc_step(&s, tick_event(), 1100 + TC_PANIC_MS);
    CHECK(count_of(boom, TC_PANIC) == 1);
}

/* ---- a lone modifier reaches the system (M6, the follow-ups spec §3) ---- */

static void test_a_lone_modifier_is_handed_through_after_the_window(void) {
    tc_state s;
    m1(&s);   /* HOLD 400 · AYN + X: AYN alone is a candidate's prefix, nothing armed */
    tc_out d = tc_step(&s, AYN_DOWN, 1000);
    CHECK(d.n == 1 && d.items[0].kind == TC_SWALLOW);
    CHECK(d.deadline_ms == 1000 + TC_LONE_MS);
    tc_out early = tc_step(&s, tick_event(), 1000 + TC_LONE_MS - 1);
    CHECK(early.n == 0);
    CHECK(early.deadline_ms == 1000 + TC_LONE_MS);
    /* the window closes: the game gets AYN's down now, as a real hold */
    tc_out o = tc_step(&s, tick_event(), 1000 + TC_LONE_MS);
    CHECK(o.n == 1);
    CHECK(is(o.items[0], TC_SYNTH, TC_CODE_AYN, 1));
    CHECK(o.deadline_ms == -1);
    CHECK(s.session && s.passed);
    /* and the session is the game's: X is forwarded, nothing fires, the up is forwarded */
    tc_out x = tc_step(&s, X_DOWN, 2000);
    CHECK(x.n == 1 && is(x.items[0], TC_FORWARD, X_CODE, 1));
    CHECK(x.deadline_ms == -1);
    CHECK(is(tc_step(&s, X_UP, 2100).items[0], TC_FORWARD, X_CODE, 0));
    tc_out u = tc_step(&s, AYN_UP, 2200);
    CHECK(u.n == 1 && is(u.items[0], TC_FORWARD, TC_CODE_AYN, 0));
    CHECK(!s.session);
}

static void test_a_button_inside_the_window_decides_the_session(void) {
    tc_state s;
    m1(&s);
    tc_step(&s, AYN_DOWN, 1000);
    /* at the last moment: the hold arms, the window is gone */
    tc_out x = tc_step(&s, X_DOWN, 1000 + TC_LONE_MS - 1);
    CHECK(x.n == 1 && x.items[0].kind == TC_SWALLOW);
    CHECK(x.deadline_ms == 1000 + TC_LONE_MS - 1 + M1_HOLD_MS);
    CHECK(count_of(tc_step(&s, tick_event(), 1000 + TC_LONE_MS), TC_SYNTH) == 0);
    CHECK(fires(tc_step(&s, tick_event(), 1000 + TC_LONE_MS - 1 + M1_HOLD_MS), 1, 0));
}

static void test_a_bare_press_fires_under_the_window_and_not_past_it(void) {
    table_begin(1);
    tc_control b[] = { K(X_CODE) };
    int bare = rule(M_AYN, PRESS, 0, NULL, 0);
    rule(M_AYN, HOLD, 400, b, 1);
    tc_state s;
    fresh(&s);
    tc_step(&s, AYN_DOWN, 1000);
    CHECK(fires(tc_step(&s, AYN_UP, 1000 + TC_LONE_MS - 1), 1, (uint16_t)bare));
    /* held past the window: the system's, not the tap's */
    tc_state s2;
    fresh(&s2);
    CHECK(tc_step(&s2, AYN_DOWN, 1000).deadline_ms == 1000 + TC_LONE_MS);
    tc_out o = tc_step(&s2, tick_event(), 1000 + TC_LONE_MS);
    CHECK(o.n == 1 && is(o.items[0], TC_SYNTH, TC_CODE_AYN, 1));
    tc_out u = tc_step(&s2, AYN_UP, 2000);
    CHECK(u.n == 1 && is(u.items[0], TC_FORWARD, TC_CODE_AYN, 0));
    CHECK(count_of(u, TC_FIRE) == 0);
    CHECK(count_of(u, TC_SYNTH) == 0);
}

static void test_a_bare_hold_leaves_no_window(void) {
    /* the user bound the hold: the rule's deadline is the only one, however long */
    table_begin(1);
    rule(M_AYN, HOLD, TC_LONE_MS + 300, NULL, 0);
    tc_state s;
    fresh(&s);
    CHECK(tc_step(&s, AYN_DOWN, 1000).deadline_ms == 1000 + TC_LONE_MS + 300);
    tc_out at_window = tc_step(&s, tick_event(), 1000 + TC_LONE_MS);
    CHECK(at_window.n == 0);
    CHECK(at_window.deadline_ms == 1000 + TC_LONE_MS + 300);
    CHECK(fires(tc_step(&s, tick_event(), 1000 + TC_LONE_MS + 300), 1, 0));
    CHECK(count_of(tc_step(&s, AYN_UP, 2000), TC_SWALLOW) == 1);
}

static void test_a_second_modifier_restarts_the_window(void) {
    table_begin(1);
    tc_control b[] = { K(X_CODE) };
    rule(M_AYN | M_HOME, PRESS, 0, b, 1);
    tc_state s;
    fresh(&s);
    CHECK(tc_step(&s, AYN_DOWN, 1000).deadline_ms == 1000 + TC_LONE_MS);
    tc_out h = tc_step(&s, HOME_DOWN, 1400);
    CHECK(h.n == 1 && h.items[0].kind == TC_SWALLOW);
    CHECK(h.deadline_ms == 1400 + TC_LONE_MS);
    CHECK(tc_step(&s, tick_event(), 1000 + TC_LONE_MS).n == 0);
    CHECK(fires(tc_step(&s, X_DOWN, 1400 + TC_LONE_MS - 1), 1, 0));
}

static void test_a_modifier_held_after_a_fire_is_not_handed_through(void) {
    table_begin(1);
    tc_control b[] = { K(X_CODE) };
    rule(M_AYN, PRESS, 0, b, 1);
    tc_state s;
    fresh(&s);
    tc_step(&s, AYN_DOWN, 1000);
    CHECK(fires(tc_step(&s, X_DOWN, 1050), 1, 0));
    tc_out xu = tc_step(&s, X_UP, 1100);
    CHECK(count_of(xu, TC_SWALLOW) == 1);
    CHECK(xu.deadline_ms == -1);              /* the system must never see this AYN */
    CHECK(tc_step(&s, tick_event(), 5000).n == 0);
    CHECK(count_of(tc_step(&s, AYN_UP, 5100), TC_SWALLOW) == 1);
}

static void test_modifiers_no_set_wants_are_handed_through_at_the_window(void) {
    /* AYN and Home are both modifiers of the table, but no rule wants exactly the pair:
       held together with no button they are still a session, and the game's at the window */
    table_begin(1);
    tc_control b[] = { K(X_CODE) };
    rule(M_AYN, PRESS, 0, b, 1);
    rule(M_HOME, PRESS, 0, b, 1);
    tc_state s;
    fresh(&s);
    tc_step(&s, AYN_DOWN, 1000);
    tc_out h = tc_step(&s, HOME_DOWN, 1100);
    CHECK(h.n == 1 && h.items[0].kind == TC_SWALLOW);
    CHECK(h.deadline_ms == 1100 + TC_LONE_MS);
    tc_out o = tc_step(&s, tick_event(), 1100 + TC_LONE_MS);
    CHECK(o.n == 2);
    CHECK(is(o.items[0], TC_SYNTH, TC_CODE_AYN, 1));
    CHECK(is(o.items[1], TC_SYNTH, TC_CODE_HOME, 1));
}

static void test_releasing_the_modifier_with_a_button_swallowed_passes_through(void) {
    table_begin(1);
    tc_control b[] = { K(X_CODE) };
    rule(M_AYN, HOLD, 400, b, 1);
    tc_state s;
    fresh(&s);
    tc_step(&s, AYN_DOWN, 1000);
    tc_step(&s, X_DOWN, 1050);
    tc_out o = tc_step(&s, AYN_UP, 1200);   /* abandoned before the hold */
    CHECK(o.n == 3);
    CHECK(is(o.items[0], TC_SYNTH, TC_CODE_AYN, 1));
    CHECK(is(o.items[1], TC_SYNTH, X_CODE, 1));
    CHECK(is(o.items[2], TC_FORWARD, TC_CODE_AYN, 0));
    CHECK(o.deadline_ms == -1);
    CHECK(is(tc_step(&s, X_UP, 1300).items[0], TC_FORWARD, X_CODE, 0));
    CHECK(!s.session);
}

static void test_the_worst_pass_through_fits_the_decision_bound(void) {
    table_begin(1);
    tc_control b[] = { K(A_CODE), K(B_CODE), K(X_CODE), K(Y_CODE) };
    rule(M_AYN | M_HOME | M_BACK | M_START | M_SELECT, HOLD, 400, b, 4);
    tc_state s;
    fresh(&s);
    tc_step(&s, AYN_DOWN, 1000);
    tc_step(&s, HOME_DOWN, 1010);
    tc_step(&s, BACK_DOWN, 1020);
    tc_step(&s, START_DOWN, 1030);
    tc_step(&s, SELECT_DOWN, 1040);
    tc_step(&s, A_DOWN, 1050);
    tc_step(&s, B_DOWN, 1060);
    tc_step(&s, X_DOWN, 1070);
    CHECK(tc_step(&s, Y_DOWN, 1080).deadline_ms == 1080 + 400);   /* armed */
    /* a fifth button nothing wants: five modifiers and five buttons go out, at once */
    tc_out o = tc_step(&s, L1_DOWN, 1090);
    CHECK(o.n == 10);
    CHECK(count_of(o, TC_SYNTH) == 10);
    CHECK(o.n <= TC_MAX_DECISIONS);
}

/* ---- the fixtures, through the real parser ---- */

static void load_table(const char *name, uint32_t generation, tc_table *t) {
    uint8_t buf[TC_TABLE_MAX_BYTES];
    size_t n = load(name, buf, sizeof buf);
    CHECK(tc_table_parse(buf, n, generation, t, NULL) == TC_TABLE_OK);
}

static void test_kitchen_sink_fixture_semantics(void) {
    static tc_table t;
    load_table("kitchen-sink.bin", 2147483647u, &t);
    tc_state s;
    tc_init(&s);
    CHECK(tc_matcher_set_table(&s, &t) == TC_SET_INSTALLED);
    /* rule 1: HOME held 500 ms fires; a Home tap is Home's own */
    tc_step(&s, HOME_DOWN, 1000);
    CHECK(fires(tc_step(&s, tick_event(), 1500), 2147483647u, 1));
    tc_step(&s, HOME_UP, 1600);
    tc_step(&s, HOME_DOWN, 2000);
    CHECK(count_of(tc_step(&s, HOME_UP, 2100), TC_SYNTH) == 2);
    /* rule 2: AYN + all four, fires only on the fourth */
    tc_step(&s, AYN_DOWN, 3000);
    CHECK(count_of(tc_step(&s, A_DOWN, 3010), TC_FIRE) == 0);
    CHECK(count_of(tc_step(&s, L1_DOWN, 3020), TC_FIRE) == 0);
    CHECK(count_of(tc_step(&s, R1_DOWN, 3030), TC_FIRE) == 0);
    CHECK(fires(tc_step(&s, VOLUP_DOWN, 3040), 2147483647u, 2));
    tc_step(&s, key(TC_DEV_GPIO, VOLUP, 0), 3050);
    tc_step(&s, key(TC_DEV_PAD, R1_CODE, 0), 3060);
    tc_step(&s, key(TC_DEV_PAD, L1_CODE, 0), 3070);
    tc_step(&s, A_UP, 3080);
    tc_step(&s, AYN_UP, 3090);
    CHECK(!s.session);
    /* rule 0 wants HAT0X at -1 AND +1 — impossible on one axis — so it never fires
       and a D-pad press under Select+Start passes through cleanly */
    tc_step(&s, SELECT_DOWN, 4000);
    tc_step(&s, START_DOWN, 4010);
    CHECK(count_of(tc_step(&s, axis(16, -1), 4020), TC_SWALLOW) == 1);   /* a prefix */
    tc_out o = tc_step(&s, axis(16, 0), 4030);
    CHECK(count_of(o, TC_SYNTH) == 3);        /* Select, Start, HAT at -1 */
    CHECK(count_of(o, TC_FORWARD) == 1);      /* the real release */
    CHECK(count_of(o, TC_FIRE) == 0);
}

static void test_bootstrap_fixture_semantics(void) {
    static tc_table t;
    load_table("bootstrap.bin", 1, &t);
    tc_state s;
    tc_init(&s);
    CHECK(tc_matcher_set_table(&s, &t) == TC_SET_INSTALLED);
    /* 0: hold AYN + X */
    tc_step(&s, AYN_DOWN, 1000);
    tc_step(&s, X_DOWN, 1050);
    CHECK(fires(tc_step(&s, tick_event(), 1450), 1, 0));
    tc_step(&s, X_UP, 1500);
    tc_step(&s, AYN_UP, 1550);
    /* 1: AYN + D-pad left */
    tc_step(&s, AYN_DOWN, 2000);
    CHECK(fires(tc_step(&s, axis(16, -1), 2050), 1, 1));
    tc_step(&s, axis(16, 0), 2100);
    tc_step(&s, AYN_UP, 2150);
    /* 2: a bare AYN tap */
    tc_step(&s, AYN_DOWN, 3000);
    CHECK(fires(tc_step(&s, AYN_UP, 3100), 1, 2));
    /* and AYN + A is nobody's: the game gets both */
    tc_step(&s, AYN_DOWN, 4000);
    tc_out o = tc_step(&s, A_DOWN, 4050);
    CHECK(o.n == 2 && count_of(o, TC_SYNTH) == 2);
    CHECK(is(tc_step(&s, A_UP, 4100).items[0], TC_FORWARD, A_CODE, 0));
    CHECK(is(tc_step(&s, AYN_UP, 4150).items[0], TC_FORWARD, TC_CODE_AYN, 0));
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
    RUN(test_press_rule_fires_on_the_completing_down);
    RUN(test_a_modifier_no_rule_uses_is_an_ordinary_button);
    RUN(test_exact_modifier_set_distinguishes_chords);
    RUN(test_exact_set_not_subset_a_superset_hold_passes_through);
    RUN(test_modifiers_must_all_be_down_before_the_first_button);
    RUN(test_multi_button_press_waits_for_the_last_button);
    RUN(test_abandoned_prefix_passes_through_as_a_tap);
    RUN(test_bare_press_fires_on_release_and_not_on_down);
    RUN(test_bare_hold_arms_on_down_and_a_tap_synthesises);
    RUN(test_bare_press_and_bare_hold_coexist);
    RUN(test_fire_once_per_rule_not_per_session);
    RUN(test_lowest_index_wins_and_press_beats_hold);
    RUN(test_dpad_axis_is_a_button);
    RUN(test_analog_threshold_with_hysteresis);
    RUN(test_hat_flip_releases_one_control_and_presses_the_other);
    RUN(test_an_abandoned_axis_press_is_synthesised_at_its_threshold);
    RUN(test_table_swap_is_deferred_until_the_session_closes);
    RUN(test_until_a_table_is_installed_everything_is_forwarded);
    RUN(test_outside_a_session_every_event_is_forwarded_verbatim);
    RUN(test_panic_works_while_both_its_keys_are_modifiers);
    RUN(test_releasing_the_modifier_with_a_button_swallowed_passes_through);
    RUN(test_the_worst_pass_through_fits_the_decision_bound);
    RUN(test_kitchen_sink_fixture_semantics);
    RUN(test_bootstrap_fixture_semantics);
    RUN(test_unbound_axis_is_forwarded);
    RUN(test_a_lone_modifier_is_handed_through_after_the_window);
    RUN(test_a_button_inside_the_window_decides_the_session);
    RUN(test_a_bare_press_fires_under_the_window_and_not_past_it);
    RUN(test_a_bare_hold_leaves_no_window);
    RUN(test_a_second_modifier_restarts_the_window);
    RUN(test_a_modifier_held_after_a_fire_is_not_handed_through);
    RUN(test_modifiers_no_set_wants_are_handed_through_at_the_window);

    printf("\n%d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
