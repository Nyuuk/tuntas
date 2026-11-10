#!/usr/bin/env python3
"""Explicit operator-only provisioning. Never executed by Actions. No secret output."""
import base64
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys

ROOT = Path.home() / '.hermes/secrets/tuntas-release'
REPO = 'Nyuuk/tuntas'
NAMES = {'TUNTAS_KEYSTORE_BASE64', 'TUNTAS_KEYSTORE_PASSWORD', 'TUNTAS_KEY_ALIAS', 'TUNTAS_KEY_PASSWORD'}


def check_existing(root, remote_names):
    if not root.exists() and NAMES.intersection(remote_names):
        raise ValueError('Remote signing secrets exist without local backup; refusing key generation')
    if root.is_symlink():
        raise ValueError('Backup must not be a symlink')


def run(args, **kwargs):
    result = subprocess.run(args, capture_output=True, **kwargs)
    if result.returncode:
        raise ValueError('Provisioning command failed; private state retained for operator inspection')
    return result.stdout


def provision():
    os.umask(0o077)
    remote = json.loads(run(['gh', 'secret', 'list', '--repo', REPO, '--json', 'name']))
    check_existing(ROOT, {item['name'] for item in remote})
    if not ROOT.exists():
        ROOT.mkdir(mode=0o700, parents=True, exist_ok=False)
        password = secrets.token_urlsafe(48)
        for name, value in [('store-password', password), ('key-password', password), ('alias', 'tuntas-release')]:
            with (ROOT / name).open('x') as file:
                file.write(value)
        run(['keytool', '-genkeypair', '-keystore', str(ROOT / 'release.p12'), '-storetype', 'PKCS12',
             '-alias', 'tuntas-release', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
             '-dname', 'CN=Tuntas Release', '-storepass:file', str(ROOT / 'store-password'),
             '-keypass:file', str(ROOT / 'key-password')])
    expected = ['release.p12', 'store-password', 'key-password', 'alias']
    if ROOT.stat().st_mode & 0o777 != 0o700:
        raise ValueError('Backup directory permissions must be 0700')
    for name in expected:
        path = ROOT / name
        if path.is_symlink() or not path.is_file() or path.stat().st_mode & 0o777 != 0o600:
            raise ValueError('Incomplete or insecure backup: operator recovery required; never regenerate')
    certificate = run(['keytool', '-list', '-v', '-keystore', str(ROOT / 'release.p12'),
                       '-alias', (ROOT / 'alias').read_text(), '-storepass:file', str(ROOT / 'store-password')])
    import re
    match = re.search(rb'SHA256: ([0-9A-F:]+)', certificate)
    if not match:
        raise ValueError('Cannot obtain public certificate fingerprint')
    fingerprint = match[1].decode().replace(':', '').lower()
    values = {'TUNTAS_KEYSTORE_BASE64': base64.b64encode((ROOT / 'release.p12').read_bytes()),
              'TUNTAS_KEYSTORE_PASSWORD': (ROOT / 'store-password').read_bytes(),
              'TUNTAS_KEY_ALIAS': (ROOT / 'alias').read_bytes(),
              'TUNTAS_KEY_PASSWORD': (ROOT / 'key-password').read_bytes()}
    for name, value in values.items():
        run(['gh', 'secret', 'set', name, '--repo', REPO], input=value)
    readback = json.loads(run(['gh', 'secret', 'list', '--repo', REPO, '--json', 'name']))
    if not NAMES.issubset({item['name'] for item in readback}):
        raise ValueError('Secret-name readback failed')
    manifest = {'repository': REPO, 'certificate_sha256': fingerprint,
                'paths': [str(ROOT / name) for name in expected],
                'warning': 'Loss of this key prevents compatible updates. Protect a separate private offline backup.'}
    (ROOT / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print(json.dumps({'certificate_sha256': fingerprint, 'backup': str(ROOT), 'secrets_verified': sorted(NAMES)}))


if __name__ == '__main__':
    if sys.argv[1:] != ['--provision-approved-key']:
        sys.exit('Explicit --provision-approved-key required; operator authorization required')
    try:
        provision()
    except (ValueError, OSError):
        sys.exit('Signing provisioning failed closed. No key material printed; preserve private backup for recovery.')
