#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$ROOT"

python3 - <<'PY'
from pathlib import Path
import tomllib
import xml.etree.ElementTree as ET

root = Path('.')
with (root / 'gradle/libs.versions.toml').open('rb') as fh:
    tomllib.load(fh)

for path in root.rglob('*.xml'):
    if any(part in {'.gradle', 'build', '.cxx'} for part in path.parts):
        continue
    ET.parse(path)

required = [
    'settings.gradle.kts',
    'build.gradle.kts',
    'gradle.properties',
    'gradle/libs.versions.toml',
    'app/build.gradle.kts',
    'app/src/main/AndroidManifest.xml',
    'camera-core/build.gradle.kts',
    'aurora-core/build.gradle.kts',
    'aurora-core/src/main/cpp/CMakeLists.txt',
    '.github/workflows/android.yml',
]
missing = [p for p in required if not (root / p).exists()]
if missing:
    raise SystemExit('Missing required files: ' + ', '.join(missing))
print('TOML/XML/required-file checks: OK')
PY

sh -n gradlew

echo "Shell syntax: OK"
echo "Source-level validation complete. Full Android compilation still requires the Android SDK/NDK and dependency downloads."
