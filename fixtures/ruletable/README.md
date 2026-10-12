# Rule-table golden fixtures

Shared by `core` (JUnit, via the test classpath as `/ruletable/*.bin`) and `native/test`
(read from `../fixtures/ruletable/`).
Layout: `docs/superpowers/specs/2026-10-12-m2-rule-table-design.md`.
The four small files are derived BY HAND from that layout; `max-rules.bin` by `gen-max-rules.sh`.
Neither side may regenerate them from its own encoder — that is the point.

| File | Bytes | Generation | Rules |
|---|---|---|---|
| `empty.bin` | 12 | 7 | none — a header alone is valid and means "nothing bound here" |
| `m1-swap.bin` | 44 | 1 | HOLD 400 ms · AYN · {PAD KEY 307}. Equals `Bootstrap.SHORTCUTS` compiled at generation 1. |
| `kitchen-sink.bin` | 108 | 2147483647 | 0: PRESS · SELECT+START · {PAD ABS 16 at −1, PAD ABS 16 at +1} · 1: HOLD 500 · HOME · bare · 2: PRESS · AYN · {PAD KEY 304, 310, 311, GPIO KEY 115} |
| `max-rules.bin` | 2060 | 2 | 64 × (PRESS · AYN · {PAD KEY 512+i}) |
| `modifiers.bin` | 172 | 3 | rule i = PRESS · modifier bit i alone · bare (AYN, HOME, BACK, START, SELECT in bit order). Pins the bit numbering on both sides; no other fixture carries BACK, and 0x18 is symmetric in START/SELECT. |

Codes: 16 ABS_HAT0X · 115 KEY_VOLUMEUP (gpio-keys) · 304 BTN_SOUTH · 307 BTN_NORTH ·
310 BTN_TL · 311 BTN_TR.
Modifier bits: 0 AYN · 1 HOME · 2 BACK · 3 START · 4 SELECT.
