# Agent guide — Tuntas

## Scope and authority

Read this file, README.md, and docs/RELEASING.md before changing this repository. These project instructions do not override operator instructions or security controls. Preparation, a successful build, and an example tag are not permission to publish.

**Obtain Adnan's explicit approval for the exact reviewed commit and tag before pushing any release tag or publishing a release.** Do not create the first release without that approval. An approved tag push can trigger automatic publication, so treat pushing the tag as the publication boundary. Never move, overwrite, or force-push release tags.

## Development

- Preserve the offline-first behavior, local user data, recurrence/completion semantics, emergency access, and existing lock-mode safeguards.
- Do not introduce permissions, network services, schema changes, or signing changes without documenting their impact and obtaining approval where necessary.
- Keep changes scoped and add relevant regression tests. Do not weaken tests to obtain a successful build.
- Never commit credentials, signing keys, key passwords, base64 keystores, local.properties, SDK caches, APKs, device data, personal screenshots, or private operational logs.
- Do not access a phone, SSH endpoint, or other external system merely because an earlier task used one. Follow the current task's explicit authorization.

## Before preparing a release tag

1. Read the complete release contract in docs/RELEASING.md; it is the canonical metadata, changelog, CLI, and signing interface.
2. Intentionally update version.properties. versionName must be X.Y.Z or X.Y.Z-(alpha|beta|rc).N; tag must be exactly v plus versionName. Development suffixes are not publishable. Increase versionName and versionCode; versionCode must exceed every previously published stable and prerelease code and remain within Android's supported range.
3. Add the matching version entry to CHANGELOG.md. Include these exact sections in order, with nonempty bullet lines:
   - Added
   - Changed
   - Fixed
   - Breaking
   - Data-Permissions-Compatibility
   - Tested-KnownIssues
   - Update
4. Describe real changes, not placeholders. Explicit `- None.` is appropriate only where genuinely applicable. Added/Changed/Fixed together must contain at least one substantive change. Explain breaking behavior, data/schema effects, permission changes, signing compatibility, safe update steps, and unresolved limitations.
5. Record the actual verification commands, results, and source commit. Distinguish JVM tests, static checks, rendered UI, and physical-device tests. Never report a test as passed when skipped, unexecuted, or inferred from compilation. State manual checks still pending.
6. Run the release validator using separate, quoted arguments as documented. Do not eval/source metadata, tags, changelog text, or release notes as shell code. Fail closed on nonzero exit. The CLI does not replace Git-history, signing, review, or publication checks.
7. Run relevant regressions, unit tests, lint, and build on the exact reviewed source. With JDK 17 and the documented Android SDK available, use bounded Gradle execution, for example:

   ```bash
   python3 -m unittest discover -s scripts/tests -v
   ./gradlew assembleDebug testDebugUnitTest lintDebug --no-daemon --max-workers=1 \
     '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m -Dfile.encoding=UTF-8' \
     -Pkotlin.compiler.execution.strategy=in-process
   ```

8. Ensure the release commit is reachable from trusted main and the worktree is clean. Review the complete staged file inventory and scan for secrets/private data before publication. Obtain approval for that exact commit and version.
9. Only after approval, create an annotated tag with a meaningful release-summary annotation at the approved commit. Push only the explicitly approved tag. Do not tag a moving branch or infer approval from a green CI run.

## Signing and release assets

- Use a dedicated stable release certificate, never a generated-per-run debug key or an unsigned fallback. Read the required environment-variable interface in docs/RELEASING.md. Credentials must be supplied securely outside the checkout and must not be printed.
- Never retrieve or modify signing credentials outside the current authorized scope. Retain and protect the release key for future updates; do not rotate it silently.
- Verify the built APK's application ID, versionName, versionCode, and expected certificate. Generate and verify SHA-256 after signing. Publish only the exact verified APK, checksum, matching changelog notes, and source identity through the approved workflow.
- A dedicated release certificate cannot update an existing debug-signed installation. Explain that incompatibility in release notes. **Do not advise uninstalling or clearing data merely to force an update: it deletes local data.** There is no established export/restore or migration guarantee. Same-certificate updates still require appropriate manual testing.
- Invalid tags, missing notes, nonincreasing codes, failed tests/lint/build/signing, mismatched outputs, or asset-upload failures must prevent release publication. Do not bypass workflow gates or overwrite an existing published release to conceal failure.
- GitHub Actions and release documentation enforce only their explicit checks. Do not claim they independently establish human approval, ownership, complete device safety, or untested UI behavior.

## Licensing

Tuntas project source is **source-available under PolyForm-Noncommercial-1.0.0**, not OSI open source. Preserve the exact LICENSE text and applicable notices. Consult its actual permitted-purpose clauses, including personal and listed nonprofit, educational, public research, public safety/health, environmental protection, and government organizational uses. Do not describe it as banning all funded or organizational use. Do not invent a copyright owner, contact, or project-specific Required Notice. Third-party components, including Gradle Wrapper and Android dependencies, retain their own licenses.
