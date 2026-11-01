#!/usr/bin/env bash
# Copies the runtime built by bug_capsule_patch_engine/tools/build-native.sh.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="${BCPATCH_ENGINE:-$HERE/../bug_capsule_patch_engine}"
# Validate before replacing the checked-in release libraries.
python3 - "$HERE" "$ENGINE/dist/android/jniLibs" <<'PYTHON'
import json, sys
from pathlib import Path
root, source = map(Path, sys.argv[1:])
sys.path.insert(0, str(root / 'scripts'))
from validate_patch_runtime import validate
validate({str(p.relative_to(source)): p.read_bytes() for p in source.rglob('*') if p.is_file()}, json.loads((root / 'release/patch-runtime.json').read_text()))
PYTHON
rm -rf "$HERE/bugcapsule-patch/src/main/jniLibs"
cp -R "$ENGINE/dist/android/jniLibs" "$HERE/bugcapsule-patch/src/main/jniLibs"
echo "synced runtime from $ENGINE/dist"

python3 "$HERE/scripts/validate_patch_runtime.py"
