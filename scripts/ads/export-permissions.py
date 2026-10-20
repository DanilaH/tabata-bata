"""Publish permissions, never BuildConfig values or credentials."""
import json
from pathlib import Path
import xml.etree.ElementTree as ET

candidates = list(Path('app/build/intermediates/merged_manifests/release').rglob('AndroidManifest.xml'))
assert len(candidates) == 1, 'Expected one final merged release manifest'
root = ET.parse(candidates[0]).getroot()
ns = '{http://schemas.android.com/apk/res/android}'
permissions = sorted({n.attrib[ns + 'name'] for n in root.findall('uses-permission')})
Path('app/build/release-evidence/permissions.json').write_text(json.dumps(permissions, indent=2) + '\n')
print('Merged permission report written')
