#!/usr/bin/env python3
"""Independently verify signed APK identity, then create a minimal public bundle."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

CERT = 'd706c403fe12d7411d51209e693b75ac7101ad4625c79e4b4dddeb09bde0787c'


def verify_identity(badging, certificates, state):
    identity = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    if not identity or identity.groups() != ('com.tuntas.app', str(state['version_code']), state['version']):
        raise ValueError('APK application ID/version mismatch')
    certs = re.findall(r'^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]+)$', certificates, re.M)
    if [value.lower() for value in certs] != [CERT]:
        raise ValueError('APK must have exactly the dedicated stable release signer')


def package(state, apk, output, tools):
    if output.exists():
        raise ValueError('Bundle directory already exists: refusing stale/overwritten assets')
    certificate = subprocess.check_output([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)], text=True)
    badging = subprocess.check_output([str(tools / 'aapt'), 'dump', 'badging', str(apk)], text=True)
    verify_identity(badging, certificate, state)
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    if commit != state['commit']:
        raise ValueError('Build source differs from gate')
    label = state['version'] if state['mode'] == 'release' else 'validation-' + commit
    if not re.fullmatch('[A-Za-z0-9.-]+', label):
        raise ValueError('Unsafe APK filename')
    output.mkdir(parents=True)
    name = 'tuntas-' + label + '.apk'
    shutil.copyfile(apk, output / name)
    digest = hashlib.sha256((output / name).read_bytes()).hexdigest()
    provenance = {'schema_version': 1, 'commit': commit, 'tag': state.get('tag'),
                  'tag_object': state.get('tag_object'), 'version': state['version'],
                  'version_code': state['version_code'], 'mode': state['mode'],
                  'certificate_sha256': CERT, 'apk': name, 'apk_sha256': digest,
                  'run_url': f"https://github.com/Nyuuk/tuntas/actions/runs/{os.environ.get('GITHUB_RUN_ID', 'local')}",
                  'run_attempt': os.environ.get('GITHUB_RUN_ATTEMPT', 'local'),
                  'toolchain': 'JDK 17 / Android SDK 34 / build-tools 34.0.0 / Gradle 8.7'}
    (output / 'build-manifest.json').write_text(json.dumps(provenance, indent=2) + '\n')
    notes = state['notes'] + f'\n## Build provenance\n\n- Exact source commit: `{commit}`\n'
    notes += f"- Annotated tag object: `{state.get('tag_object', 'not applicable: validation only')}`\n"
    notes += f"- Build: {provenance['run_url']} (attempt {provenance['run_attempt']})\n"
    notes += f'- APK SHA-256: `{digest}`\n- Release certificate SHA-256: `{CERT}`\n'
    notes += '\n## Signing and test limitations\n\nA release-signed APK cannot update a debug-signed installation. Do not uninstall or clear data to force compatibility: that deletes local data. No established export/restore or migration guarantee exists. JVM tests and lint do not prove phone UI, physical-device behavior, or update/data preservation.\n'
    notes += '\nProject license: [PolyForm-Noncommercial-1.0.0](https://polyformproject.org/licenses/noncommercial/1.0.0). Source-available, not OSI open source. Third-party components retain their own licenses.\n'
    (output / 'release-notes.md').write_text(notes)
    files = sorted(path for path in output.iterdir() if path.is_file())
    (output / 'SHA256SUMS').write_text(''.join(hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + path.name + '\n' for path in files))
    print(json.dumps(provenance))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state', type=Path, required=True)
    parser.add_argument('--apk', type=Path, default=Path('app/build/outputs/apk/release/app-release.apk'))
    parser.add_argument('--output', type=Path, default=Path('dist'))
    parser.add_argument('--tools', type=Path, required=True)
    args = parser.parse_args()
    try:
        package(json.loads(args.state.read_text()), args.apk, args.output, args.tools)
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'APK verification failed: {error}\n')
