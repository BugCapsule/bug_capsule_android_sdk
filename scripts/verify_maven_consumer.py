#!/usr/bin/env python3
"""Compile the release lab as a separate Maven consumer; no SDK project dependency."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('--repository', type=Path, help='Maven repository; defaults to build/repo')
parser.add_argument('--output', type=Path, required=True, help='New empty consumer directory')
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
out = args.output.resolve()
if out.exists():
    raise SystemExit('Consumer output already exists; choose a fresh directory')
repo = (args.repository or root / 'build/repo').resolve()
props = dict(line.split('=', 1) for line in (root / 'gradle.properties').read_text().splitlines() if '=' in line and not line.startswith('#'))
version = props['VERSION_NAME']
out.mkdir(parents=True)
shutil.copytree(root / 'examples/patchlab/src', out / 'app/src')
(out / 'settings.gradle.kts').write_text('pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositories { maven { url = uri(' + json.dumps(repo.as_uri()) + ') }; google(); mavenCentral() } }\nrootProject.name = "external-patch-consumer"\ninclude(":app")\n')
(out / 'build.gradle.kts').write_text('''plugins {
 id("com.android.application") version "8.13.0" apply false
 id("org.jetbrains.kotlin.android") version "2.2.20" apply false
 id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}
''')
s = (root / 'examples/patchlab/build.gradle.kts').read_text()
for old, new in [('alias(libs.plugins.android.application)', 'id("com.android.application")'), ('alias(libs.plugins.kotlin.android)', 'id("org.jetbrains.kotlin.android")'), ('alias(libs.plugins.kotlin.compose)', 'id("org.jetbrains.kotlin.plugin.compose")'), ('libs.compose.bom', '"androidx.compose:compose-bom:2024.12.01"'), ('libs.compose.material3', '"androidx.compose.material3:material3"'), ('libs.activity.compose', '"androidx.activity:activity-compose:1.9.3"')]:
    s = s.replace(old, new)
s = s.replace('implementation(project(":bugcapsule-patch-compose"))', '\n'.join(f'implementation("io.github.bugcapsule:{name}:{version}")' for name in ['bugcapsule-android', 'bugcapsule-patch', 'bugcapsule-patch-compose']))
s += '\nkotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }\n'
(out / 'app/build.gradle.kts').write_text(s)
(out / 'gradle.properties').write_text('android.useAndroidX=true\norg.gradle.jvmargs=-Xmx2g\n')
with (out / 'build.log').open('w') as log:
    subprocess.run([str(root / 'gradlew'), '-p', str(out), ':app:assembleRelease', '--max-workers=2', '--no-parallel'], cwd=out, stdout=log, stderr=subprocess.STDOUT, check=True)
apk = out / 'app/build/outputs/apk/release/app-release.apk'
with zipfile.ZipFile(apk) as z:
    slices = sorted(n for n in z.namelist() if n.endswith('/libbcpatch.so'))
expected = sorted(f'lib/{abi}/libbcpatch.so' for abi in ['arm64-v8a', 'armeabi-v7a', 'x86_64'])
if slices != expected:
    raise SystemExit(f'Missing native runtime in consumer APK: {slices}')
(out / 'result.json').write_text(json.dumps({'status': 'PASS', 'version': version, 'apkSha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'nativeSlices': slices, 'deviceExecution': False, 'scope': 'Independent local Maven consumer release build, including R8'}, indent=2) + '\n')
print(out / 'result.json')
