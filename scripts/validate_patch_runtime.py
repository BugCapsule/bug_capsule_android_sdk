"""Fail closed if a release omits or changes a reviewed JNI runtime."""
import argparse
import hashlib
import json
import struct
import zipfile
from pathlib import Path

ABIS = {'arm64-v8a': (2, 183), 'armeabi-v7a': (1, 40), 'x86_64': (2, 62)}


def validate(files, manifest):
    expected = {f'{abi}/libbcpatch.so' for abi in ABIS}
    if set(files) != expected or set(manifest['files']) != expected:
        raise ValueError('Patch runtime must contain exactly the three supported JNI slices')
    for name, data in files.items():
        abi = name.split('/')[0]
        elf_class, machine = ABIS[abi]
        if len(data) < 20 or data[:4] != b'\x7fELF' or data[4] != elf_class or data[5] != 1 or struct.unpack_from('<H', data, 18)[0] != machine:
            raise ValueError(f'Wrong ELF architecture: {name}')
        if hashlib.sha256(data).hexdigest() != manifest['files'][name]:
            raise ValueError(f'Unreviewed patch runtime bytes: {name}')


def validate_source(root):
    source = root / 'bugcapsule-patch/src/main/jniLibs'
    files = {str(p.relative_to(source)): p.read_bytes() for p in source.rglob('*') if p.is_file()}
    validate(files, json.loads((root / 'release/patch-runtime.json').read_text()))


def validate_aar(aar, manifest):
    with zipfile.ZipFile(aar) as z:
        names = [n for n in z.namelist() if n.startswith('jni/') and not n.endswith('/')]
        if len(names) != len(set(names)):
            raise ValueError('Duplicate JNI entries')
        validate({n.removeprefix('jni/'): z.read(n) for n in names}, manifest)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--aar', type=Path, help='Also verify a built patch AAR')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    validate_source(root)
    if args.aar:
        validate_aar(args.aar, json.loads((root / 'release/patch-runtime.json').read_text()))
