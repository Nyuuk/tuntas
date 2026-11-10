# Tuntas release Actions — operator guide

## Workflow changes (Unreleased)

- Added exact-source, annotated-tag, trusted-main, version/history/changelog and explicit approval gates; rejected tags cannot publish.
- Added independently verified dedicated release signing, post-sign checksums and source/build provenance. Tests and lint must pass before release packaging.
- Added current-main **validation-only** dispatch; it builds/signs and uploads Actions artifacts without creating a tag or GitHub Release. Development metadata remains unchanged and is not misrepresented as publishable.
- Added draft-first publication with uploaded-byte readback, final tag-object/history rechecks and no overwrite. A failed draft stays private for operator investigation, not automatic clobber/retry.
- No application behavior, permission, schema, UI or data-storage changes. No physical-device/UI/update/data-preservation validation is established by this workflow.

Read [the release contract](../docs/RELEASING.md) and [agent rules](../AGENTS.md). This guide supplements, not replaces, the metadata validator interface. The retained development version is deliberately invalid for tag publication.

## Nonpublishing CI validation

After reviewed automation is on current `main`:

```bash
gh workflow run release.yml --repo Nyuuk/tuntas --ref main -f mode=validate-only
```

Workflow display name: **Tuntas release**. Select branch `main` and mode `validate-only` in the Actions UI. There is no publish input. Any non-main dispatch is rejected before signing. The workflow binds to the triggering exact commit and refuses if main has advanced at verification; rerun against the new reviewed main if necessary.

The final verifier must correlate the run with the exact reviewed main SHA, inspect gate/build SUCCESS and publish SKIPPED, then download `signed-apk-bundle`, verify `SHA256SUMS`, and independently inspect APK application/version/certificate. The bundle contains only:

- `tuntas-validation-<full-commit>.apk` for dispatch, or `tuntas-<validated-version>.apk` for a release;
- `SHA256SUMS` covering the APK, `build-manifest.json`, and `release-notes.md`;
- `build-manifest.json` with exact source, version, certificate, tag object when applicable and run provenance;
- `release-notes.md` with changelog (release only), provenance and signing/test limitations.

Actions artifact retention: 14 days for the bundle, 2 days for public gate metadata. No keystore, Gradle cache, arbitrary directory, test logs or environment dumps are uploaded. Validation-only artifacts are not a public Release or approval to distribute a production update. Check that project tags and GitHub Releases did not change during validation.

## Signing infrastructure

Repository Actions secret names (values never in source or documentation):

- `TUNTAS_KEYSTORE_BASE64`
- `TUNTAS_KEYSTORE_PASSWORD`
- `TUNTAS_KEY_ALIAS`
- `TUNTAS_KEY_PASSWORD`

The signing helper decodes the keystore into a private runner-temp directory, passes Forge's `TUNTAS_KEYSTORE_FILE`/password/alias environment interface to Gradle, runs release unit tests and lint, then assembles the release APK. The directory is cleaned in Python `finally` plus an Actions `always()` step. Debug verification and all other jobs have no release secrets. Only the publish job has `contents: write`; it has no signing secrets and does not run Gradle.

Stable public certificate SHA-256:

```text
d706c403fe12d7411d51209e693b75ac7101ad4625c79e4b4dddeb09bde0787c
```

The approved operator-only `scripts/provision_release_key.py --provision-approved-key` is **not called by Actions**. It refuses to generate a key when repository signing secret names already exist without the scoped local backup; existing complete backups are reused, never replaced. A partial/insecure backup fails for manual recovery. Secret writes use stdin and are followed by secret-name readback; GitHub does not expose plaintext secret readback. The encrypted PKCS12 key has neutral `CN=Tuntas Release`; it is not a debug certificate. Secure local backup directory is operator-private under `~/.hermes/secrets/tuntas-release` (0700, files 0600); the private manifest lists paths and public fingerprint only. Protect an additional private offline backup by a separately authorized process. **Losing this key/passwords prevents compatible future updates.** Never regenerate to conceal loss.

A release-signed APK cannot update an existing debug-signed installation. Do not uninstall or clear data to force compatibility: doing so deletes local data. No established export/restore or migration guarantee exists. Same-certificate updates also require honest physical-device/data-preservation testing.

## Actual release authorization (not yet granted)

1. Prepare the intentional version/code/changelog change under the canonical contract; run regressions and review the exact clean candidate commit on main.
2. Obtain Adnan's explicit approval for that **exact commit and exact tag**. A green validation run is not authorization.
3. In a separate reviewed trusted-main change, set `.github/release-policy.json` `approved_tag` and `approved_commit` to that approved pair. Keep the certificate pin unchanged. The candidate may precede this policy commit: it must remain reachable from main. Do not place the approval only in the candidate tag or dispatch inputs. Initial null values deliberately disable release publication.
4. Only the authorized operator may create/push the meaningful annotated tag at the approved candidate. No commands here authorize creating the first tag. Automation rechecks current trusted-main policy before signing and publication.
5. Tags must match `vX.Y.Z` or `vX.Y.Z-(alpha|beta|rc).N`; the Forge CLI is invoked with safe argv and every published stable/prerelease code. Unknown history fails closed. Published metadata is read from each actual tagged commit, not release-note claims.
6. Workflow-wide serialization prevents candidates on different tags from racing. Existing candidate Releases, including drafts, are refused. A retry never overwrites existing assets or tag. Investigate an incomplete draft manually; deleting/retrying it requires explicit operator approval.

Release assets are staged in a draft; the workflow reads back asset inventory and downloads/compares every uploaded byte, then checks exact annotated tag object, main reachability, approval and versionCode history again before publishing. It never creates a tag automatically (`--verify-tag`). There remains an unavoidable small external-mutation window between final Git checks and GitHub's publication API; no broad repository settings/paid protections are changed. Refs must not be moved by another writer. Administrators and writers able to replace workflow code can bypass code-level policy; documentation and repository files are not an independent human-authentication boundary. Existing repository write access and normal Actions secret protections remain trusted.

Only `v*` pushes trigger the release path; malformed matching tags fail. Nonmatching tags do not publish. Alpha/beta/rc use the validated prerelease Boolean and are not marked latest. Stable tags are marked latest only after all gates pass. No first real tag-to-public-Release end-to-end test has been authorized; local isolated fixture repositories cover tag-specific checks. CI does not prove phone UI, runtime permission behavior or update safety.

## Local verification

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s scripts/tests -v
python3 scripts/check_toolchain.py
actionlint .github/workflows/release.yml
```

The wrapper JAR and Gradle 8.7 distribution checksum pins are verified against Gradle's official distribution checksum endpoints. Actions use full commit pins with reviewed version comments; any update needs fresh official-ref verification. Toolchain: JDK 17, Android SDK 34/build-tools 34.0.0, standard `ubuntu-24.04` GitHub-hosted runners. Gradle is bounded to one worker, 1 GiB heap and in-process Kotlin; no paid/self-hosted runner is configured. Tests create tags only in disposable isolated fixture repositories.
