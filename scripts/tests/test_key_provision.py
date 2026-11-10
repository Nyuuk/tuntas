"""Safety checks for the explicit local-only key provisioning helper."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

class KeySafetyTest(unittest.TestCase):
    def test_existing_secret_without_backup_refuses_key_generation(self):
        script = Path(__file__).resolve().parents[1] / 'provision_release_key.py'
        self.assertTrue(script.exists(), 'Missing fail-closed key provisioning helper')
        spec = importlib.util.spec_from_file_location('provision', script)
        assert spec and spec.loader
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(ValueError):
                mod.check_existing(Path(directory) / 'key', {'TUNTAS_KEY_PASSWORD'})

if __name__ == '__main__':
    unittest.main()
