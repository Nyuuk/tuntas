import importlib.util
from pathlib import Path
import tempfile
import unittest
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

class BundleTest(unittest.TestCase):
    def test_missing_or_unexpected_files_fail_closed(self):
        path = Path(__file__).resolve().parents[1] / 'publish_release.py'
        self.assertTrue(path.exists(), 'Missing non-overwriting draft publication helper')
        spec = importlib.util.spec_from_file_location('publish', path)
        assert spec and spec.loader
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                mod.verify_bundle(Path(directory), {'version': '1.2.3'})
        import hashlib
        import json
        import release_assets
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = {'version': '1.2.3', 'commit': 'a' * 40, 'tag': 'v1.2.3',
                     'tag_object': 'b' * 40, 'version_code': 2, 'mode': 'release',
                     'certificate_sha256': release_assets.CERT}
            apk_name = 'tuntas-1.2.3.apk'
            # Explicit integrity fixture, NOT a real APK or signing evidence.
            (root / apk_name).write_bytes(b'integrity-fixture-only')
            manifest = dict(state, apk=apk_name, apk_sha256=hashlib.sha256((root / apk_name).read_bytes()).hexdigest())
            (root / 'build-manifest.json').write_text(json.dumps(manifest))
            (root / 'release-notes.md').write_text('Fixture notes, not a real release.')
            checksum = ''.join(hashlib.sha256(p.read_bytes()).hexdigest() + '  ' + p.name + '\n' for p in sorted(root.iterdir()))
            (root / 'SHA256SUMS').write_text(checksum)
            self.assertEqual(len(mod.verify_bundle(root, state)), 4)
            (root / apk_name).write_bytes(b'tampered-fixture')
            with self.assertRaises(ValueError):
                mod.verify_bundle(root, state)
