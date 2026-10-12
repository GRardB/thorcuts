#include "ruletable.h"

#include <stdbool.h>
#include <string.h>

/* Codes are MEASURED: docs/superpowers/findings/2026-09-30-m1-device-recon.md, control table.
   AYN is KEY_F24 on gpio-keys; the other four are on the pad. */
const tc_control tc_modifier_controls[TC_MOD_COUNT] = {
    [TC_MOD_AYN]    = { TC_RT_DEV_GPIO, TC_RT_EV_KEY, 194, 1 },
    [TC_MOD_HOME]   = { TC_RT_DEV_PAD,  TC_RT_EV_KEY, 102, 1 },
    [TC_MOD_BACK]   = { TC_RT_DEV_PAD,  TC_RT_EV_KEY, 158, 1 },
    [TC_MOD_START]  = { TC_RT_DEV_PAD,  TC_RT_EV_KEY, 315, 1 },
    [TC_MOD_SELECT] = { TC_RT_DEV_PAD,  TC_RT_EV_KEY, 314, 1 },
};

static uint16_t rd16(const uint8_t *p) {
    return (uint16_t)(p[0] | (p[1] << 8));
}

static uint32_t rd32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) |
           ((uint32_t)p[3] << 24);
}

/* Lexicographic over (dev, type, code, value), value as the signed i16 it is. This is
   the ONE canonical order; the Kotlin encoder sorts by the same key. */
static int control_cmp(tc_control a, tc_control b) {
    if (a.dev != b.dev) return a.dev < b.dev ? -1 : 1;
    if (a.type != b.type) return a.type < b.type ? -1 : 1;
    if (a.code != b.code) return a.code < b.code ? -1 : 1;
    if (a.value != b.value) return a.value < b.value ? -1 : 1;
    return 0;
}

static bool all_zero(const uint8_t *p, size_t n) {
    for (size_t i = 0; i < n; i++) {
        if (p[i] != 0) return false;
    }
    return true;
}

static tc_table_result parse_rule(const uint8_t *r, tc_rule *out) {
    uint8_t mods = r[0];
    uint8_t count = r[1];
    uint8_t style = r[2];
    uint16_t hold = rd16(r + 4);

    if (mods == 0 || (mods & (uint8_t)~TC_MOD_MASK_ALL) != 0) return TC_TABLE_MODIFIERS;
    if (count > TC_MAX_BUTTONS) return TC_TABLE_BUTTONS;
    if (style != TC_STYLE_PRESS && style != TC_STYLE_HOLD) return TC_TABLE_STYLE;
    if (style == TC_STYLE_HOLD ? hold == 0 : hold != 0) return TC_TABLE_HOLD;
    if (r[3] != 0 || r[6] != 0 || r[7] != 0) return TC_TABLE_RESERVED;

    tc_control b[TC_MAX_BUTTONS];
    memset(b, 0, sizeof b);
    for (int i = 0; i < TC_MAX_BUTTONS; i++) {
        const uint8_t *c = r + 8 + i * 6;
        if (i >= count) {
            if (!all_zero(c, 6)) return TC_TABLE_SLOT;
            continue;
        }
        b[i].dev = c[0];
        b[i].type = c[1];
        b[i].code = rd16(c + 2);
        b[i].value = (int16_t)rd16(c + 4);
        if (b[i].dev != TC_RT_DEV_PAD && b[i].dev != TC_RT_DEV_GPIO) return TC_TABLE_DEVICE;
        if (b[i].type != TC_RT_EV_KEY && b[i].type != TC_RT_EV_ABS) return TC_TABLE_TYPE;
        if (b[i].type == TC_RT_EV_KEY && b[i].value != 1) return TC_TABLE_VALUE;
        if (i > 0 && control_cmp(b[i - 1], b[i]) >= 0) return TC_TABLE_ORDER;
        for (int m = 0; m < TC_MOD_COUNT; m++) {
            if (control_cmp(b[i], tc_modifier_controls[m]) == 0) return TC_TABLE_CLASH;
        }
    }

    out->modifiers = mods;
    out->button_count = count;
    out->style = style;
    out->hold_ms = hold;
    memcpy(out->buttons, b, sizeof b);
    return TC_TABLE_OK;
}

tc_table_result tc_table_parse(const uint8_t *bytes, size_t n, uint32_t expected_generation,
                               tc_table *out, int *bad_rule) {
    if (bad_rule) *bad_rule = -1;

    if (n < TC_TABLE_HEADER) return TC_TABLE_SHORT;
    if (memcmp(bytes, "THRC", 4) != 0) return TC_TABLE_MAGIC;
    if (rd16(bytes + 4) != TC_TABLE_VERSION) return TC_TABLE_VERSION_BAD;
    uint16_t count = rd16(bytes + 6);
    if (count > TC_MAX_RULES) return TC_TABLE_COUNT;
    if (n != TC_TABLE_HEADER + (size_t)count * TC_RULE_BYTES) return TC_TABLE_LENGTH;
    uint32_t gen = rd32(bytes + 8);
    if (gen != expected_generation) return TC_TABLE_GENERATION;

    /* Build on the stack and copy out only on success, so a rejected table leaves the
       caller's table — the one the helper is holding — exactly as it was. */
    tc_table t;
    memset(&t, 0, sizeof t);
    t.generation = gen;
    t.count = count;
    for (uint16_t i = 0; i < count; i++) {
        const uint8_t *rule = bytes + TC_TABLE_HEADER + (size_t)i * TC_RULE_BYTES;
        tc_table_result r = parse_rule(rule, &t.rules[i]);
        if (r != TC_TABLE_OK) {
            if (bad_rule) *bad_rule = (int)i;
            return r;
        }
    }
    *out = t;
    return TC_TABLE_OK;
}

const char *tc_table_reason(tc_table_result r) {
    switch (r) {
    case TC_TABLE_OK:          return "ok";
    case TC_TABLE_SHORT:       return "short";
    case TC_TABLE_MAGIC:       return "magic";
    case TC_TABLE_VERSION_BAD: return "version";
    case TC_TABLE_COUNT:       return "count";
    case TC_TABLE_LENGTH:      return "length";
    case TC_TABLE_GENERATION:  return "generation";
    case TC_TABLE_MODIFIERS:   return "modifiers";
    case TC_TABLE_BUTTONS:     return "buttons";
    case TC_TABLE_STYLE:       return "style";
    case TC_TABLE_HOLD:        return "hold";
    case TC_TABLE_RESERVED:    return "reserved";
    case TC_TABLE_DEVICE:      return "device";
    case TC_TABLE_TYPE:        return "type";
    case TC_TABLE_VALUE:       return "value";
    case TC_TABLE_SLOT:        return "slot";
    case TC_TABLE_ORDER:       return "order";
    case TC_TABLE_CLASH:       return "clash";
    }
    return "unknown";
}

static int hexval(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}

int tc_hex_decode(const char *hex, uint8_t *out, size_t out_cap, size_t *out_len) {
    size_t n = strlen(hex);
    if (n % 2 != 0 || n / 2 > out_cap) return -1;
    for (size_t i = 0; i < n; i += 2) {
        int hi = hexval(hex[i]);
        int lo = hexval(hex[i + 1]);
        if (hi < 0 || lo < 0) return -1;
        out[i / 2] = (uint8_t)((hi << 4) | lo);
    }
    *out_len = n / 2;
    return 0;
}
