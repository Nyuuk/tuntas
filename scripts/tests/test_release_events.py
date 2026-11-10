"""Event/approval/history unit fixtures (network is mocked, never remote writes)."""
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_ci as ci
import publish_release as publish

class EventSafetyTest(unittest.TestCase):
    def test_main_dispatch_retains_dev_version_and_cannot_publish(self):
        sha = 'a' * 40
        def git(*args):
            if args[:1] == ('diff',):
                return ''
            if args == ('rev-parse', 'HEAD') or args == ('rev-parse', 'refs/remotes/origin/main'):
                return sha
            raise AssertionError(args)
        env = {'GITHUB_REPOSITORY': ci.REPO, 'GITHUB_EVENT_NAME': 'workflow_dispatch',
               'GITHUB_REF': 'refs/heads/main', 'GITHUB_SHA': sha, 'VALIDATION_MODE': 'validate-only'}
        with patch.dict(os.environ, env), patch.object(ci, 'refresh'), patch.object(ci, 'git', side_effect=git), patch.object(ci, 'read_properties', return_value={'versionName': '0.1.0-integrated-test', 'versionCode': '1'}):
            state = ci.preflight()
            self.assertEqual(state['version'], '0.1.0-integrated-test')
            self.assertEqual(state['version_code'], 1)
            self.assertNotIn('prerelease', state)
            self.assertNotIn('tag', state)
            with self.assertRaises(ValueError):
                publish.publish(state, Path('unused'), Path('unused'))
            for ref in ['refs/heads/feature', 'refs/tags/v1.2.3', 'refs/pull/1/merge']:
                with patch.dict(os.environ, {'GITHUB_REF': ref}):
                    with self.assertRaises(ValueError):
                        ci.preflight()
            with patch.dict(os.environ, {'VALIDATION_MODE': 'publish'}):
                with self.assertRaises(ValueError):
                    ci.preflight()

    def test_unknown_event_or_repository_refused(self):
        for repo, event in [('other/fork', 'push'), (ci.REPO, 'pull_request'), (ci.REPO, 'pull_request_target')]:
            with patch.dict(os.environ, {'GITHUB_REPOSITORY': repo, 'GITHUB_EVENT_NAME': event, 'GITHUB_SHA': 'a'*40}), patch.object(ci, 'refresh'), patch.object(ci, 'git', return_value='a'*40):
                with self.assertRaises(ValueError):
                    ci.preflight()

    def test_history_all_pages_prereleases_drafts_and_existing_release(self):
        pages = [[{'id': 1, 'tag_name': 'v1.0.0', 'draft': False, 'prerelease': False}],
                 [{'id': 2, 'tag_name': 'v1.1.0-rc.1', 'draft': False, 'prerelease': True},
                  {'id': 3, 'tag_name': 'v2.0.0', 'draft': True, 'prerelease': False}]]
        def git(*args):
            if args[0] == 'rev-parse':
                return 'a'*40
            if args == ('show', 'refs/tags/v1.0.0:version.properties'):
                return 'versionName=1.0.0\nversionCode=9'
            if args == ('show', 'refs/tags/v1.1.0-rc.1:version.properties'):
                return 'versionName=1.1.0-rc.1\nversionCode=10'
            raise AssertionError(args)
        with patch.object(ci, 'command', return_value=json.dumps(pages)), patch.object(ci, 'git', side_effect=git):
            self.assertEqual(ci.release_history('v1.1.0'), ['9', '10'])
            for tag in ['v1.0.0', 'v1.1.0-rc.1', 'v2.0.0']:
                with self.assertRaises(ValueError):
                    ci.release_history(tag)
            self.assertEqual(ci.release_history('v2.0.0', owned_draft_id=3), ['9', '10'])
        pages[0][0]['tag_name'] = 'unknown-tag'
        with patch.object(ci, 'command', return_value=json.dumps(pages)):
            with self.assertRaises(ValueError):
                ci.release_history('v3.0.0')

    def test_certificate_policy_pins_agree(self):
        import release_assets
        policy = json.loads(Path('.github/release-policy.json').read_text())
        self.assertEqual(policy['certificate_sha256'], ci.CERT)
        self.assertEqual(ci.CERT, release_assets.CERT)
        with self.assertRaises(ValueError):
            ci.check_policy({'certificate_sha256': ci.CERT}, 'v1.2.3', 'a' * 40)
