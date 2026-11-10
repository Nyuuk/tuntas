#!/usr/bin/env python3
"""Publish only a verified complete draft; never overwrite existing release/assets."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

from release_ci import REPO, command, git, preflight, refresh, verify_git, check_policy, release_history, validate_source
from release_assets import verify_identity


def verify_bundle(root, state):
    name = 'tuntas-' + state['version'] + '.apk'
    expected = {name, 'SHA256SUMS', 'release-notes.md', 'build-manifest.json'}
    if {p.name for p in root.iterdir()} != expected or any(p.is_symlink() or not p.is_file() for p in root.iterdir()):
        raise ValueError('Bundle must contain exactly four expected regular files')
    lines = (root / 'SHA256SUMS').read_text().splitlines()
    wanted = sorted(expected - {'SHA256SUMS'})
    checks = [hashlib.sha256((root / file).read_bytes()).hexdigest() + '  ' + file for file in wanted]
    if lines != checks:
        raise ValueError('Bundle SHA-256 mismatch')
    manifest = json.loads((root / 'build-manifest.json').read_text())
    for field in ['commit', 'tag', 'tag_object', 'version', 'version_code', 'mode', 'certificate_sha256']:
        if manifest[field] != state[field]:
            raise ValueError('Build provenance differs from verified gate')
    if manifest['apk'] != name or manifest['apk_sha256'] != hashlib.sha256((root / name).read_bytes()).hexdigest():
        raise ValueError('APK provenance mismatch')
    return sorted(expected)


def publish(state, root, tools):
    if os.environ.get('GITHUB_EVENT_NAME') != 'push' or state.get('mode') != 'release':
        raise ValueError('Dispatch can never publish')
    if preflight() != state:
        raise ValueError('Gate changed during build')
    names = verify_bundle(root, state)
    apk = root / ('tuntas-' + state['version'] + '.apk')
    verify_identity(command(str(tools / 'aapt'), 'dump', 'badging', str(apk)),
                    command(str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)), state)
    tag = state['tag']
    args = ['gh', 'release', 'create', tag, '--repo', REPO, '--verify-tag', '--draft',
            '--title', tag, '--notes-file', str(root / 'release-notes.md')]
    args += ['--prerelease'] if state['prerelease'] else []
    command(*args, *[str(root / name) for name in names])
    draft = json.loads(command('gh', 'api', f'repos/{REPO}/releases/tags/{tag}'))
    if not draft['draft'] or draft['prerelease'] != state['prerelease']:
        raise ValueError('Draft state mismatch')
    if sorted(asset['name'] for asset in draft['assets']) != names:
        raise ValueError('Draft asset readback mismatch; leave draft unpublished')
    # Download actual uploaded bytes and compare every asset before making public.
    with tempfile.TemporaryDirectory() as directory:
        command('gh', 'release', 'download', tag, '--repo', REPO, '--dir', directory)
        for name in names:
            if hashlib.sha256((Path(directory) / name).read_bytes()).digest() != hashlib.sha256((root / name).read_bytes()).digest():
                raise ValueError('Remote uploaded bytes differ; leave draft unpublished')
    refresh()
    verify_git(tag, state['commit'], state['tag_object'])
    check_policy(json.loads(git('show', 'refs/remotes/origin/main:.github/release-policy.json')), tag, state['commit'])
    validate_source(tag, 'refs/tags/' + tag, release_history(tag, owned_draft_id=draft['id']))
    command('gh', 'release', 'edit', tag, '--repo', REPO, '--draft=false',
            '--prerelease=' + str(state['prerelease']).lower(),
            '--latest=' + str(not state['prerelease']).lower())
    result = json.loads(command('gh', 'api', f'repos/{REPO}/releases/{draft["id"]}'))
    if result['draft'] or result['tag_name'] != tag or result['prerelease'] != state['prerelease']:
        raise ValueError('Published state readback mismatch; operator investigation required')
    print('Verified published release: ' + result['html_url'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--state', type=Path, required=True)
    parser.add_argument('--bundle', type=Path, default=Path('dist'))
    parser.add_argument('--tools', type=Path, required=True)
    args = parser.parse_args()
    try:
        publish(json.loads(args.state.read_text()), args.bundle, args.tools)
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'Publication failed closed (any incomplete draft retained, never overwritten): {error}\n')
