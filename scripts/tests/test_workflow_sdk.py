"""Prevent setup-android from requesting its obsolete default tools package."""
from pathlib import Path
import re
import unittest


class WorkflowSdkTests(unittest.TestCase):
    def test_both_sdk_setup_steps_explicitly_use_platform_tools_only(self):
        workflow = (Path(__file__).resolve().parents[2] /
                    '.github/workflows/release.yml').read_text()
        setups = re.findall(
            r'^      - uses: android-actions/setup-android@[^\n]+\n'
            r'((?:(?!      - ).*\n)*)', workflow, re.MULTILINE)
        self.assertEqual(len(setups), 2, 'Check both build and publish SDK setup')
        for job, setup in zip(('build', 'publish'), setups):
            with self.subTest(job=job):
                self.assertEqual(setup, '        with:\n'
                                 '          packages: platform-tools\n',
                                 'Override obsolete tools default explicitly')


if __name__ == '__main__':
    unittest.main()
