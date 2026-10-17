# Config golden fixtures

Read by `core`'s JUnit tests from the test classpath as `/config/<name>.json`. Nothing native
reads these. Layout: `docs/superpowers/specs/2026-10-13-m2-config-layer-design.md`, "The file".

| File | Pins |
|---|---|
| `default.json` | `ConfigCodec.encode(Defaults.CONFIG)`, byte for byte: the EMPTY first-run config (M5), and what a first run writes to the device (the device run diffs the real file against it). |
| `kitchen-sink.json` | HAND-WRITTEN and deliberately non-canonical: key order, modifier order, button order and package order all scrambled; `enabled`, `scope` and `buttons` left out where they have defaults; an unknown top-level member (`theme`) and an unknown shortcut member (`note`). Every action kind and screen target, both scopes, a bare modifier, four buttons, an ABS control at −1, two modifiers, a disabled shortcut. Decodes to `ConfigFixtures.KITCHEN_SINK` with nothing dropped. |
| `kitchen-sink.canonical.json` | The same config as the codec writes it: `encode(KITCHEN_SINK)` equals it byte for byte. |
| `actions-run.json` | The actions device run's input (actions spec): nine shortcuts, every action kind and every screen target that run exercises, each AYN + one button (the swap keeps its HOLD). `encode(ConfigFixtures.ACTIONS_RUN)` byte for byte, and it decodes back with nothing dropped. Placed on the device with `run-as` before a cold start. |

`default.json`, `kitchen-sink.canonical.json` and `actions-run.json` are the encoder's own
output, captured once when each landed and never hand-edited; `kitchen-sink.json` is the one
hand-made file and must stay that way — it is the independent derivation the decoder is pinned to.
