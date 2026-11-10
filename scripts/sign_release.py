#!/usr/bin/env python3
"""Decode only in private runner temp, release test/lint before signing, always clean."""
import base64
import binascii
import os
from pathlib import Path
import shutil
import subprocess
import sys


def sign():
    required = ['TUNTAS_KEYSTORE_BASE64', 'TUNTAS_KEYSTORE_PASSWORD', 'TUNTAS_KEY_ALIAS', 'TUNTAS_KEY_PASSWORD']
    if any(not os.environ.get(name) for name in required):
        raise ValueError('Missing signing configuration')
    private = Path(os.environ['RUNNER_TEMP']) / 'tuntas-signing'
    private.mkdir(mode=0o700, exist_ok=False)
    try:
        os.chmod(private, 0o700)
        try:
            key = base64.b64decode(os.environ['TUNTAS_KEYSTORE_BASE64'], validate=True)
        except binascii.Error:
            raise ValueError('Invalid keystore encoding') from None
        path = private / 'release.p12'
        with path.open('xb') as file:
            os.chmod(path, 0o600)
            file.write(key)
        env = os.environ.copy()
        env.pop('TUNTAS_KEYSTORE_BASE64', None)
        env.pop('GH_TOKEN', None)
        env.pop('GITHUB_TOKEN', None)
        env['TUNTAS_KEYSTORE_FILE'] = str(path)
        args = ['./gradlew', '--no-daemon', '--max-workers=1',
                '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8',
                '-Pkotlin.compiler.execution.strategy=in-process']
        # Separate invocations: no signing/packaging if release tests/lint fail.
        subprocess.run([*args, ':app:testReleaseUnitTest', ':app:lintRelease'], env=env, check=True)
        subprocess.run([*args, ':app:assembleRelease'], env=env, check=True)
    finally:
        shutil.rmtree(private)


if __name__ == '__main__':
    try:
        sign()
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError):
        sys.exit('Release signing failed closed. Private signing directory cleaned; no secret diagnostics emitted.')
