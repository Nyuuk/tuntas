import importlib.util
from pathlib import Path
import tempfile
import unittest

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
