#!/bin/sh
# Cross-compile thorcutsd for the Thor and drop it where AGP will package it.
#
# It is named libthorcutsd.so so that AGP treats it as a native library: only
# lib*.so under jniLibs gets packaged, and with useLegacyPackaging=true Android
# extracts it into the app's nativeLibraryDir, which permits exec. It is in fact
# a position-independent EXECUTABLE, not a shared library.
set -eu
cd "$(dirname "$0")"

: "${ANDROID_NDK:=$HOME/Library/Android/sdk/ndk/30.0.16248370}"
API=30
: "${HOST:=darwin-x86_64}"
CC="$ANDROID_NDK/toolchains/llvm/prebuilt/$HOST/bin/aarch64-linux-android$API-clang"

if [ ! -x "$CC" ]; then
    echo "NDK clang not found at $CC" >&2
    echo "Install NDK 30.0.16248370 via Android Studio, or set ANDROID_NDK." >&2
    exit 1
fi

OUT=../app/src/main/jniLibs/arm64-v8a/libthorcutsd.so
mkdir -p "$(dirname "$OUT")"

"$CC" -std=c11 -Wall -Wextra -Werror -O2 -fPIE -pie \
    -o "$OUT" src/matcher.c src/ruletable.c src/net.c src/evdev.c src/uinput.c \
    src/takeover.c src/main.c

echo "built $OUT"
ls -l "$OUT"
