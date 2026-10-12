#ifndef THORCUTS_RULETABLE_H
#define THORCUTS_RULETABLE_H

/*
 * The rule table: what the app compiles and the helper matches from.
 *
 * Little-endian, fixed-size records, parsed with bounded loops and no allocation:
 *
 *   header, 12 bytes   "THRC" · version u16 · rule_count u16 · generation u32
 *   rule, 32 bytes     modifiers u8 · button_count u8 · style u8 · 0 · hold_ms u16 · 0 u16
 *                      · buttons[4] × (device u8 · type u8 · code u16 · value i16)
 *
 * There is exactly one valid encoding of a table (sorted buttons, zero padding), and
 * tc_table_parse rejects every other, so the golden files in fixtures/ruletable/ pin
 * this parser and the Kotlin encoder (core/.../rules/RuleTableFormat.kt) to the same
 * bytes. The spec: docs/superpowers/specs/2026-10-12-m2-rule-table-design.md.
 *
 * This header does NOT include matcher.h: the table is the matcher's INPUT, so when the
 * matcher is rewritten to consume it, matcher.h will include this. Until then the device
 * slots and event types below duplicate TC_DEV_* / TC_EV_*; test_ruletable.c pins them.
 */

#include <stddef.h>
#include <stdint.h>

#define TC_TABLE_VERSION   1
#define TC_MAX_RULES       64
#define TC_MAX_BUTTONS     4
#define TC_TABLE_HEADER    12
#define TC_RULE_BYTES      32
#define TC_TABLE_MAX_BYTES (TC_TABLE_HEADER + TC_MAX_RULES * TC_RULE_BYTES)   /* 2060 */

/* Device slots and event types as the table encodes them. */
#define TC_RT_DEV_PAD  0
#define TC_RT_DEV_GPIO 1
#define TC_RT_EV_KEY   1
#define TC_RT_EV_ABS   3

/* The fixed five modifiers, by bit. The wire contract with ModifierKey.bit in Kotlin. */
enum {
    TC_MOD_AYN = 0,
    TC_MOD_HOME,
    TC_MOD_BACK,
    TC_MOD_START,
    TC_MOD_SELECT,
    TC_MOD_COUNT
};
#define TC_MOD_MASK_ALL 0x1F

enum { TC_STYLE_PRESS = 0, TC_STYLE_HOLD = 1 };

typedef struct {
    uint8_t  dev;    /* TC_RT_DEV_* */
    uint8_t  type;   /* TC_RT_EV_*  */
    uint16_t code;
    int16_t  value;  /* KEY: always 1. ABS: the bound value; comparing it is the matcher's. */
} tc_control;

typedef struct {
    uint8_t    modifiers;      /* bitmask over TC_MOD_*; never 0 */
    uint8_t    button_count;   /* 0..TC_MAX_BUTTONS; 0 is a bare modifier */
    uint8_t    style;          /* TC_STYLE_* */
    uint16_t   hold_ms;        /* HOLD: 1..65535. PRESS: 0. */
    tc_control buttons[TC_MAX_BUTTONS];   /* [0, button_count) valid, ascending */
} tc_rule;

typedef struct {
    uint32_t generation;
    uint16_t count;
    tc_rule  rules[TC_MAX_RULES];
} tc_table;

/* The five modifiers' controls, indexed by TC_MOD_*. The single C source for them;
   the Kotlin twin is ModifierKey, and both cite the M1 device recon's control table. */
extern const tc_control tc_modifier_controls[TC_MOD_COUNT];

typedef enum {
    TC_TABLE_OK = 0,
    TC_TABLE_SHORT,         /* fewer than TC_TABLE_HEADER bytes */
    TC_TABLE_MAGIC,         /* not "THRC" */
    TC_TABLE_VERSION_BAD,   /* not TC_TABLE_VERSION */
    TC_TABLE_COUNT,         /* rule_count > TC_MAX_RULES */
    TC_TABLE_LENGTH,        /* size != header + 32 * rule_count */
    TC_TABLE_GENERATION,    /* header generation != the one on the T line */
    TC_TABLE_MODIFIERS,     /* mask 0, or a bit above the five */
    TC_TABLE_BUTTONS,       /* button_count > TC_MAX_BUTTONS */
    TC_TABLE_STYLE,         /* not PRESS or HOLD */
    TC_TABLE_HOLD,          /* HOLD with 0, or PRESS with non-zero */
    TC_TABLE_RESERVED,      /* a reserved byte is not 0 */
    TC_TABLE_DEVICE,        /* slot not PAD or GPIO */
    TC_TABLE_TYPE,          /* not KEY or ABS */
    TC_TABLE_VALUE,         /* a KEY control whose value is not 1 */
    TC_TABLE_SLOT,          /* an unused button slot is not all zero */
    TC_TABLE_ORDER,         /* buttons not strictly ascending (covers duplicates) */
    TC_TABLE_CLASH          /* a button that is one of the five modifier controls */
} tc_table_result;

/* Parse n bytes as a table whose header must carry expected_generation.
   On TC_TABLE_OK, *out is fully written. On any failure *out is untouched; for a
   per-rule failure *bad_rule (if non-NULL) is the offending rule's index, else -1.
   Within a rule the checks run in field order, then per button in slot order.
   Never allocates. */
tc_table_result tc_table_parse(const uint8_t *bytes, size_t n, uint32_t expected_generation,
                               tc_table *out, int *bad_rule);

/* The lowercase one-word token for a result, for the "T bad" line. Never NULL or empty. */
const char *tc_table_reason(tc_table_result r);

/* Decode a hex string (either case) into out. Returns 0 and sets *out_len, or -1 for an
   odd length, a non-hex character, or more than out_cap bytes. "" decodes to 0 bytes. */
int tc_hex_decode(const char *hex, uint8_t *out, size_t out_cap, size_t *out_len);

#endif /* THORCUTS_RULETABLE_H */
