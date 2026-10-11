# Pre-commit validation - 2026-10-11

Branch: play-store-preparation. Preparation checkpoint, not production approval.

Command: `./gradlew --no-daemon clean check lintRelease assembleRelease bundleRelease`

## Results

- Debug: 749 tests; zero failures, errors or skipped tests.
- Lint Debug: zero errors; 37 warnings (not warning-free).
- Release: 596 tests; zero failures, errors or skipped tests.
- Lint Release: zero errors; 37 warnings (not warning-free).
- Release APK and unsigned AAB built successfully.
- APK identity: bukin.wavrecorder; label Bukin Mic Recorder; target SDK 36.
- AAB embedded R8 mapping matches mapping.txt byte for byte; no owner placeholder in packaged resources.
- Privacy-site helper: 7 tests passed.
- Shell syntax checks passed. Disposable release-test fixture seed, worktree canary and placeholder resolution checks passed.
- Staged files scanned for common secret patterns and forbidden recording/signing/build/cache files; none found.

## Scope limits

The full three-build signing regression harness was NOT rerun. Its fixture was updated
to seed its own unresolved-owner placeholder in its disposable repository now that
the actual app policy contains approved details. Run that full harness before release signing.
No production key was created; no artifact was signed or installed in this validation run.
No additional phone tests were performed. Play-signed device testing and final store
assets/declarations remain pending. The public privacy page was not republished.

## Artifact checksums (build outputs excluded from Git)

- app-release-unsigned.apk: `bc23f9b4057c92b9386d8a490c4c6658aac134b50c55150c8d8e5ed1f782eddb`
- app-release.aab: `aee16cf4dd2e911034cc7fd9046cdfcf4362d215dfd3fbd7aaac049a856ce614`
- mapping.txt: `d5161f595e5f4579d1e9c672ab2c87d934dd83e735d0bf6e1487ebd676b102d7`
