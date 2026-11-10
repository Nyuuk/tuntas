import importlib.util
from pathlib import Path
import unittest

class ApkIdentityTest(unittest.TestCase):
    def test_wrong_package_version_or_certificate_rejected(self):
        path = Path(__file__).resolve().parents[1] / 'release_assets.py'
        self.assertTrue(path.exists(), 'Missing independent signed APK verification')
        spec = importlib.util.spec_from_file_location('assets', path)
        assert spec and spec.loader
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        good = "package: name='com.tuntas.app' versionCode='2' versionName='1.2.3' platformBuildVersionName='14'"
        state = {'version': '1.2.3', 'version_code': 2}
        cert = 'Signer #1 certificate SHA-256 digest: ' + mod.CERT
        mod.verify_identity(good, cert, state)
        for bad in [good.replace('com.tuntas.app', 'other.app'), good.replace("versionCode='2'", "versionCode='3'"), good.replace('1.2.3', '1.2.4')]:
            with self.assertRaises(ValueError):
                mod.verify_identity(bad, cert, state)
        with self.assertRaises(ValueError):
            mod.verify_identity(good, cert.replace(mod.CERT, '0' * 64), state)
        with self.assertRaises(ValueError):
            mod.verify_identity(good, cert + '\nSigner #2 certificate SHA-256 digest: ' + mod.CERT, state)
