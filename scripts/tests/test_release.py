"""Release contract tests; stdlib only, never touch project Git tags."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "validate_release.py"


def notes(version="1.2.3"):
    return f"""# Changelog

## [{version}]
### Added
- Release metadata validation.
### Changed
- None.
### Fixed
- None.
### Breaking
- None.
### Data-Permissions-Compatibility
- Dedicated release certificate cannot update debug installs; uninstall loses data.
### Tested-KnownIssues
- JVM tests pass; physical update validation pending.
### Update
- Keep data; update only with matching certificate. No export available.
"""


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.metadata = self.root / "version.properties"
        self.changelog = self.root / "CHANGELOG.md"
        self.metadata.write_text("versionName=1.2.3\nversionCode=2\n")
        self.changelog.write_text(notes())

    def run_cli(self, tag="v1.2.3", ref=None, extra=()):
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--tag", tag, "--ref", ref or "refs/tags/" + tag,
             "--metadata", str(self.metadata), "--changelog", str(self.changelog), *extra],
            text=True, capture_output=True, cwd=self.root,
        )

    def test_valid_stable_json(self):
        result = self.run_cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        data = json.loads(result.stdout)
        self.assertEqual((data["version"], data["version_code"], data["prerelease"]), ("1.2.3", 2, False))
        self.assertEqual(data["tag"], "v1.2.3")
        self.assertIn("### Added", data["notes"])

    def assert_rejected(self, result):
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertEqual(result.stdout, "")
        self.assertIn("release validation failed:", result.stderr)

    def test_invalid_versions(self):
        for version in ("0.1.0-integrated-test", "1.2", "01.2.3", "1.2.3+build", "1.2.3-RC.1",
                        "1.2.3-rc.01", "1.2.3-rc", "1.2.3-rc.1;touch PWNED", "$(touch PWNED)"):
            with self.subTest(version=version):
                self.metadata.write_text(f"versionName={version}\nversionCode=2\n")
                self.changelog.write_text(notes(version))
                self.assert_rejected(self.run_cli(tag="v" + version))
        self.assertFalse((self.root / "PWNED").exists())

    def test_prerelease_classification(self):
        for version in ("0.1.0-alpha.0", "1.2.3-beta.12", "1.2.3-rc.1", "0.0.0"):
            with self.subTest(version=version):
                self.metadata.write_text(f"versionName={version}\nversionCode=2\n")
                self.changelog.write_text(notes(version))
                result = self.run_cli(tag="v" + version)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(json.loads(result.stdout)["prerelease"], "-" in version)

    def test_mismatched_tag_or_ref(self):
        for tag, ref in (("v1.2.4", None), ("1.2.3", None), ("v1.2.3", "refs/heads/main"),
                         ("v1.2.3;touch PWNED", None), ("v1.2.3", "refs/tags/v1.2.4")):
            with self.subTest(tag=tag, ref=ref):
                self.assert_rejected(self.run_cli(tag=tag, ref=ref))

    def test_invalid_properties(self):
        for text in ("versionName=1.2.3\n", "versionCode=2\n", "", "versionName=1.2.3\nversionCode=+2",
                     "versionName=1.2.3\nversionCode=02", "versionName=1.2.3\nversionCode=0",
                     "versionName=1.2.3\nversionCode=-1", "versionName=1.2.3\nversionCode=2100000001",
                     "versionName=1.2.3\nversionCode=two", "versionName=1.2.3\nversionCode=2\nversionCode=3",
                     "versionName=1.2.3\nversionCode=2\nunknown=x", "versionName:1.2.3\nversionCode=2"):
            with self.subTest(text=text):
                self.metadata.write_text(text)
                self.assert_rejected(self.run_cli())

    def test_missing_files(self):
        self.metadata.unlink()
        self.assert_rejected(self.run_cli())
        self.metadata.write_text("versionName=1.2.3\nversionCode=2\n")
        self.changelog.unlink()
        self.assert_rejected(self.run_cli())

    def test_punctuation_only_changes_are_empty(self):
        for placeholder in ("...", ".", "???", "---"):
            with self.subTest(placeholder=placeholder):
                self.changelog.write_text(notes().replace("Release metadata validation.", placeholder))
                self.assert_rejected(self.run_cli())

    def test_invalid_changelog(self):
        for text in ("", notes("1.2.4"), notes().replace("- Release metadata validation.", "- None."),
                     notes().replace("### Breaking\n- None.\n", ""),
                     notes().replace("### Update\n- Keep data; update only with matching certificate. No export available.\n", ""),
                     notes().replace("- Release metadata validation.", ""),
                     notes() + notes(), notes().replace("- Release metadata validation.", "- TODO"),
                     notes().replace("- JVM tests pass; physical update validation pending.", "- ")):
            with self.subTest(text=text):
                self.changelog.write_text(text)
                self.assert_rejected(self.run_cli())

    def test_exact_annotated_git_fixture_source(self):
        # This disposable repository has no remote and is unrelated to the app repo.
        repo = self.root / "repo"
        def git(*args):
            return subprocess.run(["git", "-C", str(repo), *args], text=True,
                                  capture_output=True, check=True).stdout.strip()
        subprocess.run(["git", "init", "--initial-branch=main", str(repo)],
                       check=True, capture_output=True)
        (repo / "version.properties").write_text(self.metadata.read_text())
        (repo / "CHANGELOG.md").write_text(notes())
        git("add", "version.properties", "CHANGELOG.md")
        identity = ("-c", "user.name=ReleaseFixture", "-c", "user.email=fixture@example.invalid",
                    "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false")
        git(*identity, "commit", "-m", "Fixture reviewed source")
        git(*identity, "tag", "-a", "v1.2.3", "-m", "Release fixture: metadata validation")
        self.assertEqual(git("cat-file", "-t", "refs/tags/v1.2.3"), "tag")
        git("merge-base", "--is-ancestor", "refs/tags/v1.2.3^{commit}", "refs/heads/main")
        # Extract from the exact tag, not an uncommitted modified working file.
        (repo / "version.properties").write_text("versionName=malicious\nversionCode=999\n")
        self.metadata.write_text(git("show", "refs/tags/v1.2.3:version.properties") + "\n")
        self.changelog.write_text(git("show", "refs/tags/v1.2.3:CHANGELOG.md") + "\n")
        result = self.run_cli(extra=("--previous-version-code", "1"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)["version_code"], 2)
        git(*identity, "tag", "fixture-lightweight")
        self.assertEqual(git("cat-file", "-t", "refs/tags/fixture-lightweight"), "commit")

    def test_only_selected_notes_are_returned(self):
        self.changelog.write_text("# Changelog\n\n## [Unreleased]\n- Future work.\n\n"
                                  + notes().removeprefix("# Changelog\n\n") + "\n"
                                  + notes("1.2.2").removeprefix("# Changelog\n\n"))
        result = self.run_cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        selected = json.loads(result.stdout)["notes"]
        self.assertIn("## [1.2.3]", selected)
        self.assertNotIn("Future work", selected)
        self.assertNotIn("1.2.2", selected)

    def test_previous_codes_must_increase(self):
        for code in ("2", "3", "bad", "0", "01", "2147483648"):
            with self.subTest(code=code):
                self.assert_rejected(self.run_cli(extra=("--previous-version-code", code)))
        result = self.run_cli(extra=("--previous-version-code", "1"))
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
