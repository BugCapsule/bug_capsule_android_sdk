#!/usr/bin/env bash
# Copies the runtime built by bug_capsule_patch_engine/tools/build-native.sh.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="${BCPATCH_ENGINE:-$HERE/../../bug_capsule_patch_engine}"
rm -rf "$HERE/bugcapsule-patch/src/main/jniLibs"
cp -R "$ENGINE/dist/android/jniLibs" "$HERE/bugcapsule-patch/src/main/jniLibs"
echo "synced runtime from $ENGINE/dist"
