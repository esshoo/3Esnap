#!/usr/bin/env python3
"""Offline structural checks; no Android build or SDK is implied by this script."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
checks=0

def check(condition, message):
    global checks
    checks+=1
    if not condition:
        raise AssertionError(message)

for xml in (ROOT/'app/src/main').rglob('*.xml'):
    ET.parse(xml)
    checks+=1
manifest=ET.parse(ROOT/'app/src/main/AndroidManifest.xml').getroot()
A='{http://schemas.android.com/apk/res/android}'
app=manifest.find('application')
check(app is not None and app.get(A+'usesCleartextTraffic')=='false','Cleartext web traffic must stay disabled')
check(app.get(A+'allowBackup')=='false','Library source URLs should not silently enter Android backups')
services=app.findall('service')
check(any(x.get(A+'foregroundServiceType')=='dataSync' for x in services),'Foreground service type is missing')
provider=app.find('provider')
check(provider is not None and provider.get(A+'exported')=='false','FileProvider must not be exported')
permissions=[x.get(A+'name') for x in manifest.findall('uses-permission')]
check('android.permission.MANAGE_EXTERNAL_STORAGE' not in permissions,'Do not request all-files access')
check('android.permission.FOREGROUND_SERVICE_DATA_SYNC' in permissions,'FGS data sync permission is missing')
js=(ROOT/'app/src/main/assets/www/app.js').read_text(encoding='utf-8')
html=(ROOT/'app/src/main/assets/www/index.html').read_text(encoding='utf-8')
ids=re.findall(r'\bid="([^"]+)"',html)
check(len(ids)==len(set(ids)),'Duplicate HTML IDs')
for target in set(re.findall(r"\$\('([^']+)'\)",js)):
    check(target in ids,f'JavaScript references missing HTML element: {target}')
check('innerHTML' not in js,'Render remote metadata with textContent, not innerHTML')
check('fonts.googleapis.com' not in html,'The UI should use local system fonts')
check('Content-Security-Policy' in html and "frame-src 'none'" in html,'Missing CSP / iframe restriction')
java='\n'.join(p.read_text(encoding='utf-8') for p in (ROOT/'app/src/main/java').rglob('*.java'))
check('addOption("--no-check-certificates")' not in java,'Never disable engine TLS verification')
check('downloadDirectMedia(' not in java,'The legacy MP4-renamed-as-MP3 path must not remain')
check('WebViewAssetLoader' in java,'Use a scoped HTTPS asset origin for WebView')
check('RELATIVE_PATH' in java and 'IS_PENDING' in java,'Use scoped public storage and pending-write protection')
check('ClipData.newRawUri' in java,'Sharing must carry a temporary content URI grant')
check(not re.search(r'\.setClipData\([^\n]*\)\.addFlags\(', java),
      'Intent.setClipData returns void: apply permission flags in a separate statement')
check('source_url TEXT' in java,'Missing durable source URL field')
for ref in re.findall(r'R\.drawable\.([A-Za-z0-9_]+)',java):
    check((ROOT/f'app/src/main/res/drawable/{ref}.xml').exists(),f'Missing drawable {ref}')
for name in ['ic_launcher','ic_launcher_round']:
    check((ROOT/f'app/src/main/res/mipmap-anydpi/{name}.xml').exists(),'Missing Android 7 legacy icon fallback')
workflow=(ROOT/'.github/workflows/android.yml').read_text()
for required in ['workflow_dispatch',':app:lintDebug',':app:assembleDebug','actions/upload-artifact@v4','testDebugUnitTest']:
    check(required in workflow,f'Workflow missing {required}')
gradle=(ROOT/'app/build.gradle').read_text()
check(not ('abiFilters' in gradle and 'splits {' in gradle),'Do not configure both ndk ABI filters and APK ABI splits')
for legacy in ('kotlin-stdlib-jdk7', 'kotlin-stdlib-jdk8'):
    check(f"exclude group: 'org.jetbrains.kotlin', module: '{legacy}'" in gradle,
          f'Exclude redundant legacy Kotlin stdlib artifact: {legacy}')
check('apksigner' in workflow,'Verify APK signatures before uploading build artifacts')
check('KEYSTORE_PASSWORD' in workflow and 'secrets.ANDROID_KEYSTORE_PASSWORD' in workflow,'Use secrets for release signing')
check(not list(ROOT.rglob('*.jks')) and not list(ROOT.rglob('*.keystore')),'Never distribute signing keys')
print(f'PASS: {checks} project structure/security checks. APK build still requires the Android SDK.')
