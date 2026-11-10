#!/usr/bin/env python3
"""Fail-closed Git/release gates. No signing credentials accepted by this helper."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

from validate_release import VERSION, read_properties, version_code


def validate_source(tag, ref, previous_codes):
    args = [sys.executable, 'scripts/validate_release.py', '--tag', tag, '--ref', ref,
            '--metadata', 'version.properties', '--changelog', 'CHANGELOG.md']
    for code in previous_codes:
        args.extend(['--previous-version-code', str(code)])
    return json.loads(command(*args))

REPO = 'Nyuuk/tuntas'
CERT = 'd706c403fe12d7411d51209e693b75ac7101ad4625c79e4b4dddeb09bde0787c'


def command(*args):
    return subprocess.check_output(args, text=True).strip()


def git(*args):
    # File contents are validator input, not a command-line scalar. Never strip
    # whitespace: that could silently repair malformed historical metadata.
    if args and args[0] == 'show':
        return subprocess.check_output(['git', *args], text=True)
    return command('git', *args)


def verify_git(tag, expected_commit, expected_object=None):
    if not tag.startswith('v') or not VERSION.fullmatch(tag[1:]):
        raise ValueError('Invalid release tag')
    if not re.fullmatch('[0-9a-f]{40}', expected_commit):
        raise ValueError('Expected exact source commit')
    ref = 'refs/tags/' + tag
    if git('cat-file', '-t', ref) != 'tag':
        raise ValueError('Release tag must be annotated')
    obj = git('rev-parse', ref)
    commit = git('rev-parse', ref + '^{commit}')
    if commit != expected_commit or (expected_object and obj != expected_object):
        raise ValueError('Tag moved or source commit mismatch')
    annotation = git('for-each-ref', '--format=%(contents)', ref)
    if len(annotation.strip()) < 15 or not any(c.isalpha() for c in annotation):
        raise ValueError('Tag annotation needs a meaningful release summary')
    git('merge-base', '--is-ancestor', commit, 'refs/remotes/origin/main')
    return {'commit': commit, 'tag_object': obj}


def refresh():
    # Fixed HTTPS remote: never credentials in checkout config or SSH access.
    git('fetch', '--force', '--prune', 'https://github.com/' + REPO + '.git',
        '+refs/heads/main:refs/remotes/origin/main', '+refs/tags/*:refs/tags/*')


def release_history(tag, owned_draft_id=None):
    pages = json.loads(command('gh', 'api', '--paginate', '--slurp',
                               f'repos/{REPO}/releases?per_page=100'))
    releases = [release for page in pages for release in page]
    if any(item['tag_name'] == tag and not (item['id'] == owned_draft_id and item['draft']) for item in releases):
        raise ValueError('Release already exists (including draft): no overwrite or retry mutation')
    codes = []
    for release in releases:
        if release['draft']:
            continue
        old_tag = release['tag_name']
        if not old_tag.startswith('v') or not VERSION.fullmatch(old_tag[1:]):
            raise ValueError('Unknown historical release format; operator reconciliation required')
        # Resolve every published stable/prerelease, never trust notes as the code source.
        git('rev-parse', '--verify', 'refs/tags/' + old_tag + '^{commit}')
        with tempfile.TemporaryDirectory() as directory:
            metadata = Path(directory) / 'version.properties'
            metadata.write_text(git('show', 'refs/tags/' + old_tag + ':version.properties') + '\n')
            properties = read_properties(metadata)
            if properties['versionName'] != old_tag[1:]:
                raise ValueError('Historical version/tag mismatch')
            codes.append(str(version_code(properties['versionCode'])))
    return codes


def check_policy(policy, tag, commit):
    if policy.get('certificate_sha256') != CERT:
        raise ValueError('Stable certificate policy mismatch')
    if policy.get('approved_tag') != tag or policy.get('approved_commit') != commit:
        raise ValueError('Missing exact operator-approved tag/commit in trusted main policy')


def preflight():
    if os.environ.get('GITHUB_REPOSITORY') != REPO:
        raise ValueError('Unexpected repository')
    event, ref, commit = (os.environ.get(name, '') for name in
                          ['GITHUB_EVENT_NAME', 'GITHUB_REF', 'GITHUB_SHA'])
    git('diff', '--exit-code', 'HEAD', '--')
    if git('rev-parse', 'HEAD') != commit:
        raise ValueError('Checkout does not match event commit')
    refresh()
    if event == 'workflow_dispatch':
        if ref != 'refs/heads/main' or commit != git('rev-parse', 'refs/remotes/origin/main'):
            raise ValueError('Validation dispatch must target current trusted main only')
        if os.environ.get('VALIDATION_MODE') != 'validate-only':
            raise ValueError('Explicit validation-only selection required')
        properties = read_properties(Path('version.properties'))
        return {'commit': commit, 'mode': 'validation', 'version': properties['versionName'],
                'version_code': version_code(properties['versionCode']),
                'notes': 'Validation only. Development metadata has NOT passed release-tag validation.\n',
                'certificate_sha256': CERT}
    if event != 'push' or not ref.startswith('refs/tags/v'):
        raise ValueError('Only tag pushes or main validation dispatch are supported')
    tag = ref.removeprefix('refs/tags/')
    identity = verify_git(tag, commit)
    policy = json.loads(git('show', 'refs/remotes/origin/main:.github/release-policy.json'))
    check_policy(policy, tag, commit)
    result = validate_source(tag, ref, release_history(tag))
    return {**result, **identity, 'mode': 'release', 'certificate_sha256': CERT}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['gate', 'recheck'])
    parser.add_argument('--state', type=Path, default=Path('gate.json'))
    args = parser.parse_args()
    try:
        result = preflight()
        if args.phase == 'recheck':
            previous = json.loads(args.state.read_text())
            if previous != result:
                raise ValueError('Release identity/history/metadata changed during build')
        else:
            args.state.write_text(json.dumps(result, indent=2) + '\n')
            output = os.environ.get('GITHUB_OUTPUT')
            if output:
                with open(output, 'a') as file:
                    file.write('commit=' + result['commit'] + '\n')
    except (ValueError, OSError, KeyError, subprocess.CalledProcessError) as error:
        parser.exit(1, f'Release gate failed: {error}\n')


if __name__ == '__main__':
    main()
