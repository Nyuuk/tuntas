# Releasing Tuntas — release contract v1

## Status and authority

Release preparation is not approval to publish. **Adnan must approve the exact reviewed source commit and tag before any tag push or release publication**, including the first release. No actual first tag/release is authorized by this document. `v0.1.0-rc.1` below is a conceptual example only; no tag-creation commands are provided.

This document describes the application/validator interface for the release workflow consumer. Workflow implementation must separately enforce the Git, build and publication checks below. Documentation and agent rules are not a security boundary and do not make arbitrary tags authorized.

## Version source

`version.properties` is the single application version source loaded by `app/build.gradle.kts`:

```properties
versionName=0.1.0-integrated-test
versionCode=1
```

This retained development name is intentionally **not publishable**. Make an intentional reviewed version bump before release; do not change the version during a CI run. Increase both versionName and versionCode for each release; code must exceed every previously published stable/prerelease code, not merely the latest stable code. Codes are canonical positive decimal integers from 1 through 2100000000.

Release names are exactly `X.Y.Z` or `X.Y.Z-(alpha|beta|rc).N`, with canonical decimal components (zero allowed, no leading zeros). No `+build`, spaces or other suffixes. Tag is exactly `v` + versionName; full ref is exactly `refs/tags/` + tag. Stable names yield `prerelease: false`; alpha/beta/rc yield `true`. Workflow must pass that Boolean to release publication rather than infer it again.

## Changelog and release notes

Keep meaningful change notes for user-visible code, tooling and documentation changes. Until a release is intentionally versioned, use `## [Unreleased]`. For a release use exactly one `## [X.Y.Z]` (or allowed prerelease name) heading, with these exact level-three headings once and in order:

```markdown
## [0.1.0-rc.1]

### Added
- Describe the actual new behavior.
### Changed
- None.
### Fixed
- None.
### Breaking
- None.
### Data-Permissions-Compatibility
- Explain data, permission and certificate compatibility, including the debug-to-release incompatibility.
### Tested-KnownIssues
- Exact commands/results and source SHA; distinguish JVM, device, UI and untested behavior.
### Update
- Explain a safe same-certificate update and data-loss risks. Do not promise untested migration.
```

The example is a format guide, **not release evidence**. Every section requires nonempty `- ` bullet lines, not nested bullets or free prose. Use explicit `- None.` where genuinely applicable; Added/Changed/Fixed together must contain at least one substantive change. Bare TODO/TBD/Pending/N/A placeholders are rejected. Reviewers must judge truthfulness and meaning: a parser cannot prove notes are substantive or tests passed.

Every actual release must explain that the dedicated release signing certificate cannot update installed debug-signed builds. Do not uninstall/clear data merely to force compatibility if data matters: that deletes local data. No export/restore or cross-version migration guarantee exists. Same-ID/same-certificate release updates should be installed over the existing app without clearing data, but data preservation still requires physical testing. State permission changes explicitly (or none), breaking changes, real test results, outstanding UI/device checks and update limits.

## Validation CLI (Python 3.9+, standard library)

Invoke from a checkout of the **exact tagged commit**. Supply values as separate argv; never `eval`, source metadata as shell, or interpolate event/notes text into executable shell code. In Actions put event values in environment variables first and quote their expansion. Example validation only:

```bash
python3 scripts/validate_release.py \
  --tag "$RELEASE_TAG" \
  --ref "$RELEASE_REF" \
  --metadata version.properties \
  --changelog CHANGELOG.md
```

`RELEASE_TAG` and `RELEASE_REF` are consumer inputs, not inferred defaults. They must come from the triggering tag event. Optional repeated `--previous-version-code CODE` arguments enforce strict growth against each supplied published code. **The workflow must enumerate all previously published releases and supply their codes**, or independently enforce the same comparison; omitting codes means no history comparison occurred. Only a verified empty published-release set permits omission. Missing/malformed historical metadata is a failure, not permission to ignore history.

Success: exit 0, one UTF-8 JSON object on stdout, newline terminated:

```json
{"schema_version":1,"tag":"v0.1.0-rc.1","version":"0.1.0-rc.1","version_code":2,"prerelease":true,"notes":"...matching Markdown section...\n"}
```

This JSON is illustrative, not an actual release. `version_code` is a JSON integer and `prerelease` a Boolean. `notes` includes the matching version heading and its sections only, excluding Unreleased and older entries. Parse JSON, write `notes` to a UTF-8 file, then use the release client's notes-file input; do not echo notes into shell, `$GITHUB_OUTPUT` or a command. The validator does not write files, perform Git/network operations or publish anything. Metadata is a strict subset of Java Properties: exact unique `versionName=value`/`versionCode=value`, optional blank lines and column-one `#` comments; no unknown keys, whitespace around assignments, escapes or continuations.

Validation failures: exit 1, empty stdout, diagnostic on stderr. Invalid CLI usage: argparse exit 2. Fail closed on **any nonzero** result; never consume partial output. Tests:

```bash
python3 -m unittest discover -s scripts/tests -v
```

Fixtures are private temporary files/repos; any fixture tags exist only in that disposable repository, never in the application repository or a remote.

## Git/workflow consumer obligations (not checked by metadata CLI)

Before signing or publication, the workflow must:

1. Require a tag-push event, validate the tag/ref via the CLI, fetch complete required tag/main history and inspect the exact `refs/tags/<validated-tag>` object. `git cat-file -t` must be `tag`, not `commit` (annotated, not lightweight). Require a meaningful release-summary annotation. Never move or overwrite a tag.
2. Resolve the tag's commit and prove it is the exact approved, reviewed SHA reachable from the trusted `main` ref (`git merge-base --is-ancestor`). Checkout that detached commit and require a clean tree before building. Never rebuild a moving branch, merge ref or unrelated SHA. Approval/review is an explicit human gate, not something the metadata parser can establish.
3. Retrieve the published release set (including prereleases, excluding the candidate on an approved retry), resolve their tags and read `version.properties` from each tagged commit. Fail if a release's source/code cannot be established. Compare the candidate code against all those codes. A tag list alone is not proof of publication history. Prevent concurrent releases from racing the monotonicity/publication checks.
4. Run validation, regression tests, debug build and lint before release packaging. Use one worker, no persistent daemon, heap 1 GiB, Kotlin in-process. Record the exact source SHA and actual results. Sanitize public output; never publish private logs or environment dumps.
5. Build the signed release from the same SHA, verify APK application ID/version/code and expected release certificate with Android build tools, and reject unsigned/debug-signed or mismatched outputs. The release certificate must be pinned/verified by the consumer, not assumed from an APK filename. Generate SHA256 **after signing**, verify it against the exact APK bytes, and publish APK + checksum file + the validator's matching notes. Any validation/build/test/lint/signing/verification/upload failure must prevent release publication. Stage/check every asset before publishing; do not leave a public partial release.
6. Use the JSON prerelease flag, include source commit identity in public release evidence, and honor the explicit approval gate. A checksum establishes integrity, not publisher trust. Workflow security, permissions, secret provisioning, exact-head checks and publication are consumer responsibilities; this change does not create a workflow or claim those checks already exist.

Avoid direct shell interpolation of untrusted tags, refs or notes. Use argv APIs and fully qualified, validated Git refs; do not use unvalidated values as options. Workflow must not create tags on arbitrary branch changes.

## Release signing interface

Gradle binds only these environment variables (no key generation, no project properties and no debug fallback):

| Environment variable | Meaning |
| --- | --- |
| `TUNTAS_KEYSTORE_FILE` | Absolute path to a private keystore outside the checkout |
| `TUNTAS_KEYSTORE_PASSWORD` | Keystore password |
| `TUNTAS_KEY_ALIAS` | Dedicated release alias |
| `TUNTAS_KEY_PASSWORD` | Release key password |

The authorized operator/workflow provisions the dedicated release key separately, retains it for updates, and injects these inputs without printing values. Never commit the key, base64 key, passwords or private signing logs. Do not inspect or reuse a debug keystore. Every app task whose name contains `release` depends on `:app:validateReleaseSigningInputs`: absent/blank inputs or missing file fail clearly, before packaging. Debug-only tasks do not require these variables. There is deliberately **no supported unsigned `assembleRelease`/external signing fallback** in this interface. Do not publish stale APKs from a previous failed run; build in a fresh/clean output directory and verify exact outputs.

With authorized credentials already injected, the bounded release build command is:

```bash
./gradlew :app:assembleRelease --no-daemon --max-workers=1 \
  '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process
```

Expected signed output: `app/build/outputs/apk/release/app-release.apk`. Existence/name alone is not verification. A signed production build and certificate/update checks remain unverified until the authorized release key and device test are available. Build success never grants permission to publish.

## License and distribution

Project source is source-available under **PolyForm-Noncommercial-1.0.0**, **not OSI open source**. Include the exact [LICENSE](../LICENSE) or its [official URL](https://polyformproject.org/licenses/noncommercial/1.0.0) with distributions, and preserve applicable notices. The license explicitly permits use by the listed charitable, educational, public research, public safety/health, environmental protection and government organizations regardless of funding or resulting obligations. Do not summarize it as a blanket ban on every funded, organizational or nonprofit use; consult the actual terms for the permitted purposes.

`LICENSE` is an unmodified copy of the [official project source](https://github.com/polyformproject/polyform-licenses/blob/1.0.0/PolyForm-Noncommercial-1.0.0.md). No project-specific copyright owner, contact or Required Notice has been invented; the Yoyodyne line is the upstream license's example only. Gradle Wrapper, Android dependencies and other third-party materials retain their own licenses; this license does not relicense them.
