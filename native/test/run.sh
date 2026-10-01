#!/bin/sh
# Host test run: the matcher is pure C with no syscalls, so it builds and runs
# on the development machine with the system compiler. No device, no NDK.
set -eu
cd "$(dirname "$0")/.."
mkdir -p build
cc -std=c11 -Wall -Wextra -Werror -O1 -g \
    -o build/test_matcher src/matcher.c test/test_matcher.c
exec ./build/test_matcher
