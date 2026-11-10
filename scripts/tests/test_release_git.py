"""Real Git gates in an isolated disposable repo; never project tags."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

SCRIPT = Path(__file__).resolve().parents[1] / 'release_ci.py'

class GitGateTest(unittest.TestCase):
    def test_annotated_exact_main_source(self):
        self.assertTrue(SCRIPT.exists(), 'Missing release Git verification')
        spec = importlib.util.spec_from_file_location('ci', SCRIPT)
        assert spec and spec.loader
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        with tempfile.TemporaryDirectory() as directory:
            def git(*args):
                return subprocess.check_output(['git', '-C', directory, *args], text=True).strip()
            git('init', '-q', '--initial-branch=main')
            git('config', 'user.name', 'Fixture')
            git('config', 'user.email', 'fixture@example.invalid')
            git('-c', 'commit.gpgsign=false', 'commit', '--allow-empty', '-qm', 'Fixture')
            commit = git('rev-parse', 'HEAD')
            git('update-ref', 'refs/remotes/origin/main', commit)
            git('-c', 'tag.gpgsign=false', 'tag', '-a', 'v1.2.3', '-m', 'Meaningful fixture release annotation')
            with unittest.mock.patch.object(mod, 'git', side_effect=git):
                result = mod.verify_git('v1.2.3', commit)
                self.assertEqual(result['commit'], commit)
                self.assertEqual(result['tag_object'], git('rev-parse', 'refs/tags/v1.2.3'))
                for tag, sha, obj in [('v1.2.3;touch-PWNED', commit, None), ('v1.2.3', '0' * 40, None), ('v1.2.3', commit, '0' * 40)]:
                    with self.assertRaises(ValueError):
                        mod.verify_git(tag, sha, obj)
                git('-c', 'tag.gpgsign=false', 'tag', 'v1.2.4')
                with self.assertRaises(ValueError):
                    mod.verify_git('v1.2.4', commit)
                git('-c', 'tag.gpgsign=false', 'tag', '-a', 'v1.2.5', '-m', 'short')
                with self.assertRaises(ValueError):
                    mod.verify_git('v1.2.5', commit)
                git('checkout', '--orphan', 'untrusted')
                git('-c', 'commit.gpgsign=false', 'commit', '--allow-empty', '-qm', 'Not on main')
                orphan = git('rev-parse', 'HEAD')
                git('-c', 'tag.gpgsign=false', 'tag', '-a', 'v1.2.6', '-m', 'Meaningful but untrusted source annotation')
                with self.assertRaises(subprocess.CalledProcessError):
                    mod.verify_git('v1.2.6', orphan)
                # Same-commit tag-object replacement still fails publication recheck.
                git('-c', 'tag.gpgsign=false', 'tag', '-f', '-a', 'v1.2.3', commit, '-m', 'Changed annotation after build')
                with self.assertRaises(ValueError):
                    mod.verify_git('v1.2.3', commit, result['tag_object'])
            with self.assertRaises(ValueError):
                mod.check_policy({'certificate_sha256': mod.CERT, 'approved_tag': None, 'approved_commit': None}, 'v1.2.3', commit)
            mod.check_policy({'certificate_sha256': mod.CERT, 'approved_tag': 'v1.2.3', 'approved_commit': commit}, 'v1.2.3', commit)

if __name__ == '__main__':
    unittest.main()
