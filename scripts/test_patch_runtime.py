import hashlib
import io
import struct
import unittest
import zipfile
from validate_patch_runtime import ABIS, validate, validate_aar


class RuntimeReleaseTest(unittest.TestCase):
    def setUp(self):
        self.files = {}
        for abi, (cls, machine) in ABIS.items():
            data = bytearray(64)
            data[:6] = b'\x7fELF' + bytes([cls, 1])
            struct.pack_into('<H', data, 18, machine)
            self.files[f'{abi}/libbcpatch.so'] = bytes(data)
        self.manifest = {'files': {n: hashlib.sha256(b).hexdigest() for n, b in self.files.items()}}

    def test_complete_reviewed_slices(self):
        validate(self.files, self.manifest)

    def test_missing_slices(self):
        for name in self.files:
            with self.assertRaises(ValueError):
                validate({n: b for n, b in self.files.items() if n != name}, self.manifest)

    def test_changed_binary(self):
        self.files['arm64-v8a/libbcpatch.so'] += b'changed'
        with self.assertRaisesRegex(ValueError, 'Unreviewed'):
            validate(self.files, self.manifest)

    def test_wrong_arch_even_with_matching_hash(self):
        n = 'arm64-v8a/libbcpatch.so'
        self.files[n] = self.files['x86_64/libbcpatch.so']
        self.manifest['files'][n] = hashlib.sha256(self.files[n]).hexdigest()
        with self.assertRaisesRegex(ValueError, 'architecture'):
            validate(self.files, self.manifest)

    def test_extra_unreviewed_slice_is_rejected(self):
        self.files["x86/libbcpatch.so"] = b"unknown"
        with self.assertRaises(ValueError):
            validate(self.files, self.manifest)

    def test_complete_aar(self):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as z:
            for name, data in self.files.items():
                z.writestr("jni/" + name, data)
        stream.seek(0)
        validate_aar(stream, self.manifest)

    def test_aar_without_jni_is_rejected(self):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as z:
            z.writestr('classes.jar', b'java')
        stream.seek(0)
        with self.assertRaises(ValueError):
            validate_aar(stream, self.manifest)


if __name__ == '__main__':
    unittest.main()
