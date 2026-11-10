import importlib.util
from pathlib import Path
import tempfile
import unittest

class ToolchainTest(unittest.TestCase):
    def test_modified_wrapper_is_rejected(self):
        path = Path(__file__).resolve().parents[1] / 'check_toolchain.py'
        self.assertTrue(path.exists(), 'Missing independently pinned wrapper check')
        spec = importlib.util.spec_from_file_location('toolchain', path)
        assert spec and spec.loader
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'gradle/wrapper').mkdir(parents=True)
            (root / 'gradle/wrapper/gradle-wrapper.jar').write_bytes(b'not a gradle wrapper')
            with self.assertRaises(ValueError):
                mod.check(root)
