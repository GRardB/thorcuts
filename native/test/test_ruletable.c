#include "../src/ruletable.h"
#include "../src/matcher.h"   /* for the cross-pin test ONLY; ruletable.h must not need it */
#include "check.h"

#include <stdio.h>
#include <string.h>

/* run.sh runs from native/, so the shared fixtures are one level up. */
#define FIXTURES "../fixtures/ruletable/"

/* Read a whole fixture into buf. Returns its size; a missing or oversized file fails a CHECK. */
static size_t load(const char *name, uint8_t *buf, size_t cap) {
    char path[256];
    snprintf(path, sizeof path, FIXTURES "%s", name);
    FILE *f = fopen(path, "rb");
    CHECK(f != NULL);
    if (f == NULL) return 0;
    size_t n = fread(buf, 1, cap, f);
    int extra = fgetc(f);
    fclose(f);
    CHECK(extra == EOF);   /* the whole file fitted in cap */
    return n;
}

/* ---- the fixtures parse, field for field ---- */

static void test_empty_fixture_parses(void) {
    uint8_t buf[TC_TABLE_MAX_BYTES];
    size_t n = load("empty.bin", buf, sizeof buf);
    CHECK(n == 12);
    tc_table t;
    int bad = 99;
    CHECK(tc_table_parse(buf, n, 7, &t, &bad) == TC_TABLE_OK);
    CHECK(t.generation == 7);
    CHECK(t.count == 0);
    CHECK(bad == -1);
}

static void test_m1_swap_fixture_parses(void) {
    uint8_t buf[TC_TABLE_MAX_BYTES];
    size_t n = load("m1-swap.bin", buf, sizeof buf);
    CHECK(n == 44);
    tc_table t;
    CHECK(tc_table_parse(buf, n, 1, &t, NULL) == TC_TABLE_OK);
    CHECK(t.generation == 1);
    CHECK(t.count == 1);
    CHECK(t.rules[0].modifiers == (1u << TC_MOD_AYN));
    CHECK(t.rules[0].button_count == 1);
    CHECK(t.rules[0].style == TC_STYLE_HOLD);
    CHECK(t.rules[0].hold_ms == 400);
    CHECK(t.rules[0].buttons[0].dev == TC_RT_DEV_PAD);
    CHECK(t.rules[0].buttons[0].type == TC_RT_EV_KEY);
    CHECK(t.rules[0].buttons[0].code == 307);
    CHECK(t.rules[0].buttons[0].value == 1);
}

static void test_kitchen_sink_fixture_parses(void) {
    uint8_t buf[TC_TABLE_MAX_BYTES];
    size_t n = load("kitchen-sink.bin", buf, sizeof buf);
    CHECK(n == 108);
    tc_table t;
    CHECK(tc_table_parse(buf, n, 2147483647u, &t, NULL) == TC_TABLE_OK);
    CHECK(t.generation == 2147483647u);
    CHECK(t.count == 3);
    /* rule 0: PRESS · SELECT+START · HAT0X at -1 and +1, ordered by value */
    CHECK(t.rules[0].modifiers == ((1u << TC_MOD_SELECT) | (1u << TC_MOD_START)));
    CHECK(t.rules[0].button_count == 2);
    CHECK(t.rules[0].style == TC_STYLE_PRESS);
    CHECK(t.rules[0].hold_ms == 0);
    CHECK(t.rules[0].buttons[0].type == TC_RT_EV_ABS);
    CHECK(t.rules[0].buttons[0].code == 16);
    CHECK(t.rules[0].buttons[0].value == -1);
    CHECK(t.rules[0].buttons[1].code == 16);
    CHECK(t.rules[0].buttons[1].value == 1);
    /* rule 1: HOLD 500 · HOME · bare */
    CHECK(t.rules[1].modifiers == (1u << TC_MOD_HOME));
    CHECK(t.rules[1].button_count == 0);
    CHECK(t.rules[1].style == TC_STYLE_HOLD);
    CHECK(t.rules[1].hold_ms == 500);
    /* rule 2: PRESS · AYN · four buttons, PAD before GPIO */
    CHECK(t.rules[2].modifiers == (1u << TC_MOD_AYN));
    CHECK(t.rules[2].button_count == 4);
    CHECK(t.rules[2].buttons[0].code == 304);
    CHECK(t.rules[2].buttons[1].code == 310);
    CHECK(t.rules[2].buttons[2].code == 311);
    CHECK(t.rules[2].buttons[3].dev == TC_RT_DEV_GPIO);
    CHECK(t.rules[2].buttons[3].code == 115);
    CHECK(t.rules[2].buttons[3].value == 1);
}

static void test_max_rules_fixture_parses(void) {
    uint8_t buf[TC_TABLE_MAX_BYTES];
    size_t n = load("max-rules.bin", buf, sizeof buf);
    CHECK(n == TC_TABLE_MAX_BYTES);
    tc_table t;
    CHECK(tc_table_parse(buf, n, 2, &t, NULL) == TC_TABLE_OK);
    CHECK(t.count == 64);
    CHECK(t.rules[0].buttons[0].code == 512);
    CHECK(t.rules[63].buttons[0].code == 575);
    CHECK(t.rules[63].modifiers == (1u << TC_MOD_AYN));
    CHECK(t.rules[63].style == TC_STYLE_PRESS);
}

static void test_full_u32_generation_parses(void) {
    /* The compiler never writes above Int.MAX_VALUE; the parser must still take the wire's u32. */
    uint8_t hdr[12] = { 'T', 'H', 'R', 'C', 1, 0, 0, 0, 0xff, 0xff, 0xff, 0xff };
    tc_table t;
    CHECK(tc_table_parse(hdr, sizeof hdr, 0xFFFFFFFFu, &t, NULL) == TC_TABLE_OK);
    CHECK(t.generation == 0xFFFFFFFFu);
    CHECK(t.count == 0);
}

/* ---- rejections: one fault at a time, on a copy of kitchen-sink.bin ----
   Offsets: header 12; rule i at 12 + 32*i; in a rule: 0 modifiers · 1 count · 2 style ·
   3 reserved · 4-5 hold · 6-7 reserved · 8 + 6*k button k (dev, type, code lo, code hi,
   value lo, value hi). Rule 0 is at 12, rule 1 at 44, rule 2 at 76. */

#define GEN_KS 2147483647u
#define R0 12
#define R1 44
#define R2 76

typedef struct {
    uint8_t buf[TC_TABLE_MAX_BYTES];
    size_t n;
} blob;

static blob kitchen(void) {
    blob b;
    memset(b.buf, 0, sizeof b.buf);
    b.n = load("kitchen-sink.bin", b.buf, sizeof b.buf);
    return b;
}

/* Parse b expecting a failure, and prove *out was not written on the way. */
static tc_table_result expect_bad(blob *b, uint32_t gen, int *bad_rule) {
    tc_table out;
    memset(&out, 0xA5, sizeof out);
    tc_table_result r = tc_table_parse(b->buf, b->n, gen, &out, bad_rule);
    uint8_t sentinel[sizeof out];
    memset(sentinel, 0xA5, sizeof sentinel);
    CHECK(memcmp(&out, sentinel, sizeof out) == 0);
    return r;
}

static void test_rejects_short(void) {
    blob b = kitchen();
    b.n = 11;
    int bad = 99;
    CHECK(expect_bad(&b, GEN_KS, &bad) == TC_TABLE_SHORT);
    CHECK(bad == -1);
}

static void test_rejects_magic(void) {
    blob b = kitchen();
    b.buf[0] = 'X';
    CHECK(expect_bad(&b, GEN_KS, NULL) == TC_TABLE_MAGIC);
}

static void test_rejects_version(void) {
    blob b = kitchen();
    b.buf[4] = 2;
    CHECK(expect_bad(&b, GEN_KS, NULL) == TC_TABLE_VERSION_BAD);
}

static void test_rejects_count(void) {
    blob b = kitchen();
    b.buf[6] = 65;   /* > TC_MAX_RULES; checked before the length, so this is COUNT not LENGTH */
    CHECK(expect_bad(&b, GEN_KS, NULL) == TC_TABLE_COUNT);
}

static void test_rejects_length(void) {
    blob b = kitchen();
    b.n -= 1;
    CHECK(expect_bad(&b, GEN_KS, NULL) == TC_TABLE_LENGTH);
    blob c = kitchen();
    c.n += 1;   /* buf has TC_TABLE_MAX_BYTES of room; the extra byte is 0 */
    CHECK(expect_bad(&c, GEN_KS, NULL) == TC_TABLE_LENGTH);
}

static void test_rejects_generation_mismatch(void) {
    blob b = kitchen();
    int bad = 99;
    CHECK(expect_bad(&b, GEN_KS + 1, &bad) == TC_TABLE_GENERATION);
    CHECK(bad == -1);
}

static void test_rejects_modifiers(void) {
    blob b = kitchen();
    b.buf[R0 + 0] = 0;
    int bad = 99;
    CHECK(expect_bad(&b, GEN_KS, &bad) == TC_TABLE_MODIFIERS);
    CHECK(bad == 0);
    blob c = kitchen();
    c.buf[R1 + 0] = 0x20 | 0x02;   /* a bit above the five */
    CHECK(expect_bad(&c, GEN_KS, &bad) == TC_TABLE_MODIFIERS);
    CHECK(bad == 1);
}

static void test_rejects_buttons(void) {
    blob b = kitchen();
    b.buf[R2 + 1] = 5;
    int bad = 99;
    CHECK(expect_bad(&b, GEN_KS, &bad) == TC_TABLE_BUTTONS);
    CHECK(bad == 2);
}

static void test_rejects_style(void) {
    blob b = kitchen();
    b.buf[R0 + 2] = 2;
    CHECK(expect_bad(&b, GEN_KS, NULL) == TC_TABLE_STYLE);
}

static void test_rejects_hold(void) {
    blob b = kitchen();          /* HOLD with hold_ms 0 */
    b.buf[R1 + 4] = 0;
    b.buf[R1 + 5] = 0;
    int bad = 99;
    CHECK(expect_bad(&b, GEN_KS, &bad) == TC_TABLE_HOLD);
    CHECK(bad == 1);
    blob c = kitchen();          /* PRESS with a hold_ms */
    c.buf[R0 + 4] = 1;
    CHECK(expect_bad(&c, GEN_KS, &bad) == TC_TABLE_HOLD);
    CHECK(bad == 0);
}

static void test_rejects_reserved(void) {
    blob b = kitchen();
    b.buf[R0 + 3] = 1;
    CHECK(expect_bad(&b, GEN_KS, NULL) == TC_TABLE_RESERVED);
    blob c = kitchen();
    c.buf[R2 + 7] = 0x80;
    CHECK(expect_bad(&c, GEN_KS, NULL) == TC_TABLE_RESERVED);
}

static void test_rejects_device(void) {
    blob b = kitchen();
    b.buf[R2 + 8 + 0] = 2;
    CHECK(expect_bad(&b, GEN_KS, NULL) == TC_TABLE_DEVICE);
}

static void test_rejects_type(void) {
    blob b = kitchen();
    b.buf[R2 + 8 + 1] = 2;   /* EV_REL */
    CHECK(expect_bad(&b, GEN_KS, NULL) == TC_TABLE_TYPE);
}

static void test_rejects_value(void) {
    blob b = kitchen();        /* a KEY control with value 0 */
    b.buf[R2 + 8 + 4] = 0;
    int bad = 99;
    CHECK(expect_bad(&b, GEN_KS, &bad) == TC_TABLE_VALUE);
    CHECK(bad == 2);
}

static void test_rejects_slot(void) {
    blob b = kitchen();        /* rule 1 has no buttons; dirty its first slot */
    b.buf[R1 + 8 + 2] = 1;
    int bad = 99;
    CHECK(expect_bad(&b, GEN_KS, &bad) == TC_TABLE_SLOT);
    CHECK(bad == 1);
}

static void test_rejects_order(void) {
    blob b = kitchen();        /* swap rule 2's buttons 0 and 1 */
    uint8_t tmp[6];
    memcpy(tmp, b.buf + R2 + 8, 6);
    memcpy(b.buf + R2 + 8, b.buf + R2 + 14, 6);
    memcpy(b.buf + R2 + 14, tmp, 6);
    int bad = 99;
    CHECK(expect_bad(&b, GEN_KS, &bad) == TC_TABLE_ORDER);
    CHECK(bad == 2);
    blob c = kitchen();        /* a duplicate is an ORDER fault too: not strictly ascending */
    memcpy(c.buf + R0 + 14, c.buf + R0 + 8, 6);
    CHECK(expect_bad(&c, GEN_KS, &bad) == TC_TABLE_ORDER);
    CHECK(bad == 0);
}

static void test_rejects_clash(void) {
    /* rule 2 button 0 becomes KEY_HOME (102): still in order, but it is a modifier */
    blob b = kitchen();
    b.buf[R2 + 8 + 2] = 102;
    b.buf[R2 + 8 + 3] = 0;
    int bad = 99;
    CHECK(expect_bad(&b, GEN_KS, &bad) == TC_TABLE_CLASH);
    CHECK(bad == 2);
}

/* ---- the hex decoder ---- */

static void test_hex_round_trip(void) {
    uint8_t out[4];
    size_t n = 0;
    CHECK(tc_hex_decode("00ff7f80", out, sizeof out, &n) == 0);
    CHECK(n == 4);
    CHECK(out[0] == 0x00 && out[1] == 0xff && out[2] == 0x7f && out[3] == 0x80);
}

static void test_hex_accepts_uppercase(void) {
    uint8_t out[2];
    size_t n = 0;
    CHECK(tc_hex_decode("ABcd", out, sizeof out, &n) == 0);
    CHECK(n == 2 && out[0] == 0xab && out[1] == 0xcd);
}

static void test_hex_refuses_odd_length(void) {
    uint8_t out[4];
    size_t n = 0;
    CHECK(tc_hex_decode("abc", out, sizeof out, &n) != 0);
}

static void test_hex_refuses_non_hex(void) {
    uint8_t out[4];
    size_t n = 0;
    CHECK(tc_hex_decode("0g", out, sizeof out, &n) != 0);
    CHECK(tc_hex_decode("0 ", out, sizeof out, &n) != 0);
}

static void test_hex_refuses_overflow(void) {
    char hex[4123];
    memset(hex, 'a', 4122);
    hex[4122] = '\0';
    uint8_t out[TC_TABLE_MAX_BYTES];
    size_t n = 0;
    CHECK(tc_hex_decode(hex, out, sizeof out, &n) != 0);   /* 2061 bytes into 2060 */
    hex[4120] = '\0';
    CHECK(tc_hex_decode(hex, out, sizeof out, &n) == 0);   /* exactly 2060 fits */
    CHECK(n == TC_TABLE_MAX_BYTES);
}

static void test_hex_empty_is_zero_bytes(void) {
    uint8_t out[1];
    size_t n = 7;
    CHECK(tc_hex_decode("", out, sizeof out, &n) == 0);
    CHECK(n == 0);
}

/* ---- reasons, and the cross-pin to matcher.h ---- */

/* The tokens are the "T bad <token>" wire vocabulary the app parses, so every one of the
   eighteen is pinned exactly, not just shape-checked. */
static void test_every_reason_is_one_token(void) {
    static const struct { tc_table_result r; const char *token; } expected[] = {
        { TC_TABLE_OK, "ok" },                 { TC_TABLE_SHORT, "short" },
        { TC_TABLE_MAGIC, "magic" },           { TC_TABLE_VERSION_BAD, "version" },
        { TC_TABLE_COUNT, "count" },           { TC_TABLE_LENGTH, "length" },
        { TC_TABLE_GENERATION, "generation" }, { TC_TABLE_MODIFIERS, "modifiers" },
        { TC_TABLE_BUTTONS, "buttons" },       { TC_TABLE_STYLE, "style" },
        { TC_TABLE_HOLD, "hold" },             { TC_TABLE_RESERVED, "reserved" },
        { TC_TABLE_DEVICE, "device" },         { TC_TABLE_TYPE, "type" },
        { TC_TABLE_VALUE, "value" },           { TC_TABLE_SLOT, "slot" },
        { TC_TABLE_ORDER, "order" },           { TC_TABLE_CLASH, "clash" },
    };
    size_t n = sizeof expected / sizeof expected[0];
    CHECK(n == (size_t)TC_TABLE_CLASH + 1);   /* every result has a row */
    for (size_t i = 0; i < n; i++) {
        const char *s = tc_table_reason(expected[i].r);
        CHECK(s != NULL && strcmp(s, expected[i].token) == 0);
        CHECK(strchr(s, ' ') == NULL);
    }
}

static void test_modifier_controls_match_matcher_h(void) {
    /* ruletable.h does not include matcher.h on purpose (the table is the matcher's
       input). Until the matcher rewrite makes matcher.h include ruletable.h, these
       constants exist twice; this is what keeps them equal. */
    CHECK(TC_RT_DEV_PAD == TC_DEV_PAD);
    CHECK(TC_RT_DEV_GPIO == TC_DEV_GPIO);
    CHECK(TC_RT_EV_KEY == TC_EV_KEY);
    CHECK(TC_RT_EV_ABS == TC_EV_ABS);
    CHECK(tc_modifier_controls[TC_MOD_AYN].dev == TC_DEV_GPIO);
    CHECK(tc_modifier_controls[TC_MOD_AYN].code == TC_CODE_AYN);
    CHECK(tc_modifier_controls[TC_MOD_HOME].dev == TC_DEV_PAD);
    CHECK(tc_modifier_controls[TC_MOD_HOME].code == TC_CODE_HOME);
    CHECK(tc_modifier_controls[TC_MOD_SELECT].dev == TC_DEV_PAD);
    CHECK(tc_modifier_controls[TC_MOD_SELECT].code == TC_CODE_SELECT);
    /* BACK and START have no matcher.h twin yet; the M1 findings are their source. */
    CHECK(tc_modifier_controls[TC_MOD_BACK].code == 158);
    CHECK(tc_modifier_controls[TC_MOD_START].code == 315);
    for (int m = 0; m < TC_MOD_COUNT; m++) {
        CHECK(tc_modifier_controls[m].type == TC_RT_EV_KEY);
        CHECK(tc_modifier_controls[m].value == 1);
    }
}

int main(void) {
    printf("tc_ruletable\n");
    RUN(test_empty_fixture_parses);
    RUN(test_m1_swap_fixture_parses);
    RUN(test_kitchen_sink_fixture_parses);
    RUN(test_max_rules_fixture_parses);
    RUN(test_full_u32_generation_parses);
    RUN(test_rejects_short);
    RUN(test_rejects_magic);
    RUN(test_rejects_version);
    RUN(test_rejects_count);
    RUN(test_rejects_length);
    RUN(test_rejects_generation_mismatch);
    RUN(test_rejects_modifiers);
    RUN(test_rejects_buttons);
    RUN(test_rejects_style);
    RUN(test_rejects_hold);
    RUN(test_rejects_reserved);
    RUN(test_rejects_device);
    RUN(test_rejects_type);
    RUN(test_rejects_value);
    RUN(test_rejects_slot);
    RUN(test_rejects_order);
    RUN(test_rejects_clash);
    RUN(test_hex_round_trip);
    RUN(test_hex_accepts_uppercase);
    RUN(test_hex_refuses_odd_length);
    RUN(test_hex_refuses_non_hex);
    RUN(test_hex_refuses_overflow);
    RUN(test_hex_empty_is_zero_bytes);
    RUN(test_every_reason_is_one_token);
    RUN(test_modifier_controls_match_matcher_h);

    printf("\n%d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
