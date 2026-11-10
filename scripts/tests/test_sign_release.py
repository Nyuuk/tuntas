import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

class SigningCleanupTest(unittest.TestCase):
    def test_malformed_keystore_fails_and_cleans_private_directory(self):
        path = Path(__file__).resolve().parents[1] / 'sign_release.py'
        self.assertTrue(path.exists(), 'Missing cleanup-safe signing helper')
        spec = importlib.util.spec_from_file_location('signing', path)
        assert spec and spec.loader
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        with tempfile.TemporaryDirectory() as directory:
            env = {'RUNNER_TEMP': directory, 'TUNTAS_KEYSTORE_BASE64': 'invalid***',
                   'TUNTAS_KEYSTORE_PASSWORD': 'fixture', 'TUNTAS_KEY_ALIAS': 'fixture',
                   'TUNTAS_KEY_PASSWORD': 'fixture'}
            with patch.dict(os.environ, env):
                with self.assertRaises(ValueError):
                    mod.sign()
            self.assertFalse((Path(directory) / 'tuntas-signing').exists())
