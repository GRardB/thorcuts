#!/bin/sh
# Host test run: the matcher and the rule-table parser are pure C with no syscalls, so
# they build and run on the development machine with the system compiler. No device,
# no NDK. One binary per subject; both must pass.
set -eu
cd "$(dirname "$0")/.."
mkdir -p build
CFLAGS="-std=c11 -Wall -Wextra -Werror -O1 -g"
cc $CFLAGS -o build/test_matcher   src/matcher.c   test/test_matcher.c
cc $CFLAGS -o build/test_ruletable src/ruletable.c test/test_ruletable.c
./build/test_matcher
./build/test_ruletable
