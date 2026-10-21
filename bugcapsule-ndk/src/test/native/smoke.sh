#!/bin/sh
# Host smoke test of the native crash handler core (not part of Gradle).
# Compiles src/main/cpp/bugcapsule_ndk.c with the host clang, crashes forked
# children (null write -> SIGSEGV/SIGBUS, abort -> SIGABRT) and validates
# the crash records.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
cpp="$here/../../main/cpp"
out=$(mktemp -d "${TMPDIR:-/tmp}/bugcapsule-ndk-smoke.XXXXXX")
trap 'rm -rf "$out"' EXIT

${CC:-cc} -std=gnu11 -g -O1 -Wall -Wextra -funwind-tables -fno-omit-frame-pointer \
    -I"$cpp" "$cpp/bugcapsule_ndk.c" "$here/smoke_main.c" -o "$out/smoke"

"$out/smoke" "$out"
