#!/usr/bin/env python3
"""Prepare a Central Portal bundle locally; never upload it."""
import argparse, hashlib, shutil, subprocess, zipfile
from pathlib import Path
parser = argparse.ArgumentParser()
parser.add_argument('--sign-key', help='Existing local GPG key ID; required for upload-ready bundle')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
subprocess.run([str(root / 'gradlew'), 'lintRelease', 'publishAll'], cwd=root, check=True)
props = dict(line.split('=', 1) for line in (root / 'gradle.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
version = props['VERSION_NAME']
repo = root / 'build/repo'
stage = root / 'build/central-bundle'
if stage.exists(): shutil.rmtree(stage)
stage.mkdir()
poms = list(repo.rglob(f'*-{version}.pom'))
assert poms, 'No Maven publications found'
for pom in poms:
    directory = pom.parent
    # Plugin markers are POM-only; runtime modules publish AAR, Gradle plugin JAR.
    if '<packaging>pom</packaging>' not in pom.read_text():
        assert any((directory / (pom.stem + suffix)).is_file() for suffix in ('.aar', '.jar')), f'Missing binary for {pom}'
        for suffix in ('-sources.jar', '-javadoc.jar'):
            assert (directory / (pom.stem + suffix)).is_file(), f'Missing {pom.stem + suffix}'
    for source in directory.iterdir():
        if source.suffix not in ('.pom', '.aar', '.jar', '.module'): continue
        target = stage / source.relative_to(repo)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
        if args.sign_key:
            subprocess.run(['gpg', '--batch', '--yes', '--armor', '--detach-sign', '--local-user', args.sign_key, str(target)], check=True)
for item in list(stage.rglob('*')):
    if not item.is_file(): continue
    for algorithm in ('md5', 'sha1', 'sha256'):
        item.with_name(item.name + '.' + algorithm).write_text(hashlib.new(algorithm, item.read_bytes()).hexdigest())
name = f'bugcapsule-{version}-' + ('signed' if args.sign_key else 'UNSIGNED-REVIEW') + '.zip'
archive = root / 'build' / name
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as output:
    for item in sorted(stage.rglob('*')):
        if item.is_file(): output.write(item, item.relative_to(stage))
print(archive)
if not args.sign_key: print('Not upload-ready: rerun with --sign-key after configuring your existing GPG key.')
