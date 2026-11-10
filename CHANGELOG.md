# Changelog

Changes are unreleased until intentionally versioned, reviewed and approved. This file does not announce an actual release. See [release contract](docs/RELEASING.md).

## [Unreleased]

### Added
- Single `version.properties` application version source and a standard-library JSON release validator with positive/negative fixtures, including a disposable Git repository test.
- Release contract documentation for exact-source builds, annotated tags, monotonic published version codes, signed APK verification, SHA256 and meaningful release notes.
- Unmodified PolyForm-Noncommercial-1.0.0 license text from the official project source; project source is source-available, not OSI open source. Third-party materials retain their own licenses.

### Changed
- Gradle release signing reads only the four documented TUNTAS signing environment variables. Release tasks fail on missing inputs instead of producing an unsigned release; debug-only tasks need no release secrets.
- README explains approval, release signing, safe update limits and license terms. Application version remains `0.1.0-integrated-test` / code `1`, intentionally rejected for tag publication.

### Fixed
- None.

### Breaking
- Release tasks now require authorized release signing inputs; unsigned release builds are deliberately unsupported. No application behavior or data schema changes.

### Data-Permissions-Compatibility
- No application ID, SDK, permissions, runtime UI or storage changes. A dedicated release certificate cannot update installed debug-signed builds. This preparation does not generate a key or claim production signing was tested.

### Tested-KnownIssues
- `python3 -m unittest discover -s scripts/tests -v`: 11 tests pass, including malformed SemVer/properties, tag/ref mismatch, empty notes, prerelease detection, code monotonicity and shell-metacharacter rejection. Fixture tags exist only in a private temporary Git repository.
- Bounded offline `assembleDebug testDebugUnitTest lintDebug` succeeds. A subsequent `testDebugUnitTest lintDebug --rerun-tasks` succeeds with 33 executed tasks; lint reports no errors/warnings. Existing compiler warnings remain for the unused scheduling `label` parameter and nullable ProtectionDomain receiver in persistence tests.
- `:app:assembleRelease` with all four signing variables absent fails at `validateReleaseSigningInputs`, before release packaging. The current development metadata is rejected by the release validator as intended.
- Verification uses `--offline --no-daemon --max-workers=1`, JVM heap 1 GiB / metaspace 384 MiB, Kotlin in-process. Exact full command and source commit identity belong in the implementation handoff; these are local checks, not release or device evidence.
- No production key, signed release APK, tag push, publication or physical-device validation performed. Update/data preservation and latest locked UI acceptance remain unverified. Workflow implementation and its Git/publication enforcement are separate consumer work.

### Update
- For a compatible same-certificate release update, install over the existing app without clearing data and validate preservation on a device. Do not uninstall merely to force debug-to-release compatibility if data matters: uninstall/clear-data loses local tasks and settings. No export/restore or cross-version migration guarantee exists.
