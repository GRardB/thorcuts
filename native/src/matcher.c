#include "matcher.h"

#include <string.h>

/* ---- output ---- */

static void push(tc_out *o, tc_kind kind, tc_event ev) {
    if (o->n >= TC_MAX_DECISIONS) return;
    o->items[o->n].kind = kind;
    o->items[o->n].ev = ev;
    o->items[o->n].generation = 0;
    o->items[o->n].index = 0;
    o->n++;
}

static void push_fire(tc_out *o, uint32_t generation, uint16_t index) {
    if (o->n >= TC_MAX_DECISIONS) return;
    memset(&o->items[o->n], 0, sizeof o->items[o->n]);
    o->items[o->n].kind = TC_FIRE;
    o->items[o->n].generation = generation;
    o->items[o->n].index = index;
    o->n++;
}

static tc_event no_event(void) {
    tc_event e;
    memset(&e, 0, sizeof e);
    return e;
}

/* The event that "presses" (value 1 / the threshold) or "releases" (0) a control. */
static tc_event ev_of(tc_control c, int32_t value) {
    tc_event e;
    e.dev = c.dev;
    e.type = c.type;
    e.code = c.code;
    e.value = value;
    return e;
}

static bool ctl_eq(tc_control a, tc_control b) {
    return a.dev == b.dev && a.type == b.type && a.code == b.code && a.value == b.value;
}

/* The sooner of two deadlines, treating -1 as "none". */
static int64_t earlier(int64_t a, int64_t b) {
    if (a < 0) return b;
    if (b < 0) return a;
    return a < b ? a : b;
}

/* ---- the table ---- */

static void install(tc_state *s, const tc_table *t) {
    s->table = *t;
    s->mods = 0;
    s->naxes = 0;
    for (uint16_t i = 0; i < t->count; i++) {
        const tc_rule *r = &t->rules[i];
        s->mods |= r->modifiers;
        for (int k = 0; k < r->button_count; k++) {
            tc_control c = r->buttons[k];
            if (c.type != TC_RT_EV_ABS) continue;
            bool seen = false;
            for (int a = 0; a < s->naxes && !seen; a++) seen = ctl_eq(s->axes[a].ctl, c);
            if (seen || s->naxes >= TC_MAX_AXES) continue;
            s->axes[s->naxes].ctl = c;
            s->axes[s->naxes].pressed = false;
            s->naxes++;
        }
    }
}

void tc_init(tc_state *s) {
    memset(s, 0, sizeof *s);
    s->armed = -1;
    s->hold_deadline_ms = -1;
    s->panic_deadline_ms = -1;
}

tc_set_result tc_matcher_set_table(tc_state *s, const tc_table *t) {
    if (s->session) {
        s->pending = *t;
        s->has_pending = true;
        return TC_SET_DEFERRED;
    }
    install(s, t);
    s->has_pending = false;
    return TC_SET_INSTALLED;
}

/* ---- the session ---- */

static void disarm(tc_state *s) {
    s->armed = -1;
    s->hold_deadline_ms = -1;
}

static void fire(tc_state *s, tc_out *o, int rule) {
    push_fire(o, s->table.generation, (uint16_t)rule);
    s->fired = true;
    disarm(s);
}

static int find_pressed(const tc_state *s, tc_control c) {
    for (int i = 0; i < s->npressed; i++) {
        if (ctl_eq(s->pressed[i].ctl, c)) return i;
    }
    return -1;
}

static void remove_pressed(tc_state *s, int i) {
    for (int k = i + 1; k < s->npressed; k++) s->pressed[k - 1] = s->pressed[k];
    s->npressed--;
}

static bool rule_has(const tc_rule *r, tc_control c) {
    for (int k = 0; k < r->button_count; k++) {
        if (ctl_eq(r->buttons[k], c)) return true;
    }
    return false;
}

/* Hand the session to the game: every held modifier down (bit order), then every
   swallowed button down (press order), each exactly once. After this the session is
   the game's; everything further is forwarded. */
static void pass_through(tc_state *s, tc_out *o) {
    for (int m = 0; m < TC_MOD_COUNT; m++) {
        uint8_t bit = (uint8_t)(1u << m);
        if ((s->held & bit) && !(s->synthed & bit)) {
            push(o, TC_SYNTH, ev_of(tc_modifier_controls[m], 1));
            s->synthed |= bit;
        }
    }
    for (int i = 0; i < s->npressed; i++) {
        if (s->pressed[i].synthed) continue;
        tc_control c = s->pressed[i].ctl;
        push(o, TC_SYNTH, ev_of(c, c.type == TC_RT_EV_KEY ? 1 : c.value));
        s->pressed[i].synthed = true;
    }
    s->passed = true;
    disarm(s);
}

typedef enum { EV_FIRED, EV_ARMED, EV_WAITING, EV_PASSED, EV_NONE } eval_result;

/* What (held, pressed) means against the installed table. The spec's decision
   table, in one place: an exact PRESS with a button down fires (lowest index; PRESS
   beats HOLD on equal sets); an exact HOLD arms (a bare HOLD included); a candidate
   waits; nothing, with buttons swallowed and nothing fired, passes the session
   through. A bare PRESS is NOT fired here — it fires on the modifier's release. */
static eval_result evaluate(tc_state *s, tc_out *o, int64_t now_ms) {
    disarm(s);
    if (s->held == 0) return EV_NONE;

    int ex_press = -1;
    int ex_hold = -1;
    bool candidate = false;
    for (uint16_t i = 0; i < s->table.count; i++) {
        const tc_rule *r = &s->table.rules[i];
        if (r->modifiers != s->held) continue;
        bool subset = true;
        for (int p = 0; p < s->npressed && subset; p++) subset = rule_has(r, s->pressed[p].ctl);
        if (!subset) continue;
        candidate = true;
        if (r->button_count != s->npressed) continue;
        if (r->style == TC_STYLE_PRESS) {
            if (ex_press < 0) ex_press = i;
        } else if (ex_hold < 0) {
            ex_hold = i;
        }
    }

    if (ex_press >= 0 && s->npressed > 0) {
        fire(s, o, ex_press);
        return EV_FIRED;
    }
    if (ex_hold >= 0) {
        s->armed = ex_hold;
        s->hold_deadline_ms = now_ms + s->table.rules[ex_hold].hold_ms;
        return EV_ARMED;
    }
    if (candidate) return EV_WAITING;
    if (s->npressed > 0 && !s->fired && !s->passed) {
        pass_through(s, o);
        return EV_PASSED;
    }
    return EV_NONE;
}

/* ---- buttons: any control that is not a modifier of this table ---- */

static void button_down(tc_state *s, tc_out *o, tc_event ev, tc_control c, int64_t now_ms) {
    if (!s->session || s->passed) {
        push(o, TC_FORWARD, ev);
        return;
    }
    if (find_pressed(s, c) >= 0) {
        /* a second down without an up: treat it as the repeat it effectively is */
        push(o, TC_SWALLOW, ev);
        return;
    }
    if (s->npressed >= TC_MAX_PRESSED) {
        pass_through(s, o);
        push(o, TC_FORWARD, ev);
        return;
    }
    s->pressed[s->npressed].ctl = c;
    s->pressed[s->npressed].synthed = false;
    s->npressed++;

    eval_result r = evaluate(s, o, now_ms);
    if (r == EV_PASSED) return;  /* pass_through synthesised c's down with the rest */
    if (r == EV_NONE) {
        /* Nothing can match and no pass-through is allowed (a rule already fired,
           or no modifier is held any more): the game gets this one as a plain
           press, and it is not ours to track. */
        remove_pressed(s, s->npressed - 1);
        push(o, TC_FORWARD, ev);
        return;
    }
    push(o, TC_SWALLOW, ev);
}

/* `emit` is false when one axis reading both released this control and pressed
   another: the bookkeeping happens here, the emission is the press's. */
static void button_up(tc_state *s, tc_out *o, tc_event ev, tc_control c, bool emit) {
    int i = find_pressed(s, c);
    if (i < 0) {
        /* its down was forwarded (no session, or passed), so its up is too */
        if (emit) push(o, TC_FORWARD, ev);
        return;
    }
    if (s->pressed[i].synthed) {
        remove_pressed(s, i);
        if (emit) push(o, TC_FORWARD, ev);
        return;
    }
    if (s->fired) {
        /* the game never saw the down, so it must not see the up */
        if (s->armed >= 0 && rule_has(&s->table.rules[s->armed], c)) disarm(s);
        remove_pressed(s, i);
        if (emit) push(o, TC_SWALLOW, ev);
        return;
    }
    /* Released before anything fired: the combo was abandoned, so the game gets
       the whole gesture. c is still in `pressed`, so pass_through synthesises its
       down among the others; then its real up is forwarded. */
    pass_through(s, o);
    remove_pressed(s, i);
    if (emit) push(o, TC_FORWARD, ev);
}

static void button_repeat(tc_state *s, tc_out *o, tc_event ev, tc_control c) {
    int i = find_pressed(s, c);
    bool swallowed = i >= 0 && !s->pressed[i].synthed;
    push(o, swallowed ? TC_SWALLOW : TC_FORWARD, ev);
}

/* ---- modifiers of this table ---- */

/* The TC_MOD_* index of ev if it is a modifier key the installed table USES, else -1.
   A modifier key no rule uses is an ordinary button here, in any app. */
static int modifier_of(const tc_state *s, tc_event ev) {
    if (ev.type != TC_EV_KEY) return -1;
    for (int m = 0; m < TC_MOD_COUNT; m++) {
        tc_control c = tc_modifier_controls[m];
        if (ev.dev != c.dev || ev.code != c.code) continue;
        return (s->mods & (1u << m)) ? m : -1;
    }
    return -1;
}

static void handle_modifier(tc_state *s, tc_out *o, tc_event ev, int m, int64_t now_ms) {
    uint8_t bit = (uint8_t)(1u << m);

    if (ev.value == 1) {
        if (s->held & bit) {
            push(o, (s->synthed & bit) ? TC_FORWARD : TC_SWALLOW, ev);
            return;
        }
        s->session = true;
        s->held |= bit;
        if (s->passed) {
            /* the game owns this session; its normal job is intact */
            s->synthed |= bit;
            push(o, TC_FORWARD, ev);
            return;
        }
        push(o, TC_SWALLOW, ev);
        evaluate(s, o, now_ms);
        return;
    }

    if (ev.value == 2) {
        push(o, (s->synthed & bit) ? TC_FORWARD : TC_SWALLOW, ev);
        return;
    }

    /* up */
    if (!(s->held & bit)) {
        push(o, TC_FORWARD, ev);
        return;
    }
    if (s->synthed & bit) {
        s->held &= (uint8_t)~bit;
        s->synthed &= (uint8_t)~bit;
        push(o, TC_FORWARD, ev);
        return;
    }
    if (s->fired) {
        s->held &= (uint8_t)~bit;
        disarm(s);
        push(o, TC_SWALLOW, ev);
        return;
    }
    if (s->npressed == 0) {
        /* a bare PRESS on exactly the held set fires on release */
        for (uint16_t i = 0; i < s->table.count; i++) {
            const tc_rule *r = &s->table.rules[i];
            if (r->modifiers != s->held || r->button_count != 0) continue;
            if (r->style != TC_STYLE_PRESS) continue;
            fire(s, o, i);
            s->held &= (uint8_t)~bit;
            push(o, TC_SWALLOW, ev);
            return;
        }
        /* a tap: the job the system was waiting for, deferred to release */
        s->held &= (uint8_t)~bit;
        push(o, TC_SYNTH, ev_of(tc_modifier_controls[m], 1));
        push(o, TC_SYNTH, ev_of(tc_modifier_controls[m], 0));
        evaluate(s, o, now_ms);  /* a bare HOLD on what is still held may arm */
        return;
    }
    /* buttons swallowed, nothing fired: abandoned by releasing the modifier. Pass
       through BEFORE clearing the bit, so this modifier's down goes out too. */
    pass_through(s, o);
    s->held &= (uint8_t)~bit;
    s->synthed &= (uint8_t)~bit;
    push(o, TC_FORWARD, ev);
}

/* ---- axes ---- */

static void handle_abs(tc_state *s, tc_out *o, tc_event ev, int64_t now_ms) {
    int release = -1;
    int press = -1;
    bool bound = false;
    for (int a = 0; a < s->naxes; a++) {
        tc_axis *x = &s->axes[a];
        if (x->ctl.dev != ev.dev || x->ctl.code != ev.code) continue;
        bound = true;
        int32_t v = x->ctl.value;
        int64_t m = v < 0 ? -(int64_t)ev.value : (int64_t)ev.value;
        int64_t t = v < 0 ? -(int64_t)v : (int64_t)v;
        if (!x->pressed && m >= t) {
            x->pressed = true;
            press = a;
        } else if (x->pressed && 4 * m < 3 * t) {
            x->pressed = false;
            release = a;
        }
    }

    if (release < 0 && press < 0) {
        /* No edge. Swallowed only while a control on this axis is swallowed-down. */
        if (bound && s->session) {
            for (int i = 0; i < s->npressed; i++) {
                tc_control c = s->pressed[i].ctl;
                if (c.type == TC_RT_EV_ABS && c.dev == ev.dev && c.code == ev.code &&
                    !s->pressed[i].synthed) {
                    push(o, TC_SWALLOW, ev);
                    return;
                }
            }
        }
        push(o, TC_FORWARD, ev);
        return;
    }
    if (release >= 0) button_up(s, o, ev, s->axes[release].ctl, press < 0);
    if (press >= 0) button_down(s, o, ev, s->axes[press].ctl, now_ms);
}

/* ---- panic ---- */

/* Arm or disarm the panic combo from the RAW events, whatever the table says. */
static void track_panic(tc_state *s, tc_event ev, int64_t now_ms) {
    bool touched = false;
    if (ev.dev == TC_DEV_PAD && ev.type == TC_EV_KEY && ev.code == TC_CODE_HOME) {
        s->home_held = ev.value != 0;
        touched = true;
    }
    if (ev.dev == TC_DEV_PAD && ev.type == TC_EV_KEY && ev.code == TC_CODE_SELECT) {
        s->select_held = ev.value != 0;
        touched = true;
    }
    if (!touched) return;

    if (s->home_held && s->select_held) {
        if (s->panic_deadline_ms < 0) s->panic_deadline_ms = now_ms + TC_PANIC_MS;
    } else {
        s->panic_deadline_ms = -1;
    }
}

/* ---- the step ---- */

tc_out tc_step(tc_state *s, tc_event ev, int64_t now_ms) {
    tc_out o;
    memset(&o, 0, sizeof o);
    o.deadline_ms = -1;

    track_panic(s, ev, now_ms);

    /* Panic is checked BEFORE any rule, and cannot be shadowed by one. */
    if (s->panic_deadline_ms >= 0 && now_ms >= s->panic_deadline_ms) {
        push(&o, TC_PANIC, no_event());
        return o;  /* no deadline: main.c is on its way out */
    }

    if (ev.type == TC_EV_TICK) {
        if (s->armed >= 0 && now_ms >= s->hold_deadline_ms) fire(s, &o, s->armed);
    } else if (ev.type == TC_EV_KEY) {
        int m = modifier_of(s, ev);
        if (m >= 0) {
            handle_modifier(s, &o, ev, m, now_ms);
        } else {
            tc_control c;
            c.dev = ev.dev;
            c.type = TC_RT_EV_KEY;
            c.code = ev.code;
            c.value = 1;
            if (ev.value == 1) button_down(s, &o, ev, c, now_ms);
            else if (ev.value == 2) button_repeat(s, &o, ev, c);
            else button_up(s, &o, ev, c, true);
        }
    } else if (ev.type == TC_EV_ABS) {
        handle_abs(s, &o, ev, now_ms);
    } else {
        push(&o, TC_FORWARD, ev);
    }

    /* The session closes when no modifier is held and no swallowed button is still
       down; a table that arrived meanwhile is installed now. */
    if (s->session && s->held == 0 && s->npressed == 0) {
        s->session = false;
        s->fired = false;
        s->passed = false;
        s->synthed = 0;
        disarm(s);
        if (s->has_pending) {
            install(s, &s->pending);
            s->has_pending = false;
        }
    }

    o.deadline_ms = earlier(s->hold_deadline_ms, s->panic_deadline_ms);
    return o;
}
