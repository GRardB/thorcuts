#!/bin/sh
# Emits max-rules.bin: generation 2, 64 rules, rule i = PRESS · AYN · {PAD KEY 512+i}.
#
# Derived from the layout table in the spec, not from the compiler: the Kotlin golden
# test must reproduce this file, and the C test must parse it. 64 rules is TC_MAX_RULES,
# so this is also the full protocol-line budget (2060 bytes = 4120 hex characters).
set -eu
cd "$(dirname "$0")"
{
    # THRC · version 1 · 64 rules (0x0040) · generation 2
    printf '54485243 0100 4000 02000000\n'
    i=0
    while [ "$i" -lt 64 ]; do
        # modifiers AYN (0x01) · 1 button · PRESS · reserved · hold 0 · reserved
        printf '01 01 00 00 0000 0000\n'
        # button 0: PAD · KEY · code 512+i = 0x02xx little-endian · value 1
        printf '00 01 %02x02 0100\n' "$i"
        # slots 1-3 unused
        printf '000000000000 000000000000 000000000000\n'
        i=$((i + 1))
    done
} | xxd -r -p > max-rules.bin
ls -l max-rules.bin
