#!/usr/bin/env python3
"""Copy built APKs to artifact folders; never call an unsigned APK installable."""
from pathlib import Path
import hashlib
import re
import shutil

ROOT = Path(__file__).resolve().parents[1]
version_match = re.search(r"versionName\s+'([^']+)'", (ROOT/'app/build.gradle').read_text())
if not version_match:
    raise SystemExit('versionName was not found in app/build.gradle')
version = version_match.group(1)
for variant in ('debug', 'release'):
    files = sorted((ROOT/f'app/build/outputs/apk/{variant}').glob('*.apk'))
    files = [f for f in files if 'unsigned' not in f.name]
    if not files:
        if variant == 'debug':
            raise SystemExit('No debug APK was produced; refusing to upload an empty artifact.')
        continue
    destination = ROOT/'dist'/variant
    destination.mkdir(parents=True, exist_ok=True)
    checksums = []
    for src in files:
        name = '3Esnap-' + version + '-' + src.name.removeprefix('app-')
        target = destination/name
        shutil.copy2(src, target)
        checksums.append(f'{hashlib.sha256(target.read_bytes()).hexdigest()}  {name}')
        print(f'{variant}: {target.relative_to(ROOT)} ({target.stat().st_size:,} bytes)')
    (destination/'SHA256SUMS.txt').write_text('\n'.join(checksums)+'\n', encoding='utf-8')
