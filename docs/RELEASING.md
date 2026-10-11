# Releasing to Google Play

How to build, sign, record and upload a release. Read it all before the first
upload: some decisions, like the application ID and the upload key, are
permanent.

## Keys: Play App Signing and the upload key

The app uses **Play App Signing**, Google's default for new apps:

- **App signing key.** Generated and kept by Google. It signs the APKs that
  Play delivers to users. You never hold it.
- **Upload key.** Generated and kept by you. You sign each upload with it,
  and Play checks it before accepting the bundle. If it's lost or leaked, you
  can ask Google to reset it from Play Console. That's possible because the
  app signing key stays safe with Google.

Rules for the upload key:

- Generate it yourself, on a machine you control. This repository does not
  generate it and does not choose its certificate details.
- Keep the keystore **outside this repository**, for example in
  `~/secure/wavrecorder/`. The script below refuses a keystore inside the
  working tree, and `.gitignore` ignores keystore file types anywhere in the
  repo as a second line of defence.
- Keep the keystore and its passwords in a password manager or another
  encrypted backup. Don't email them, don't put them in chat or tickets, and
  don't write them in shell commands, scripts, `gradle.properties` or this
  documentation.
- Use a 2048-bit or larger RSA key, valid for 25+ years.

Generate it with `keytool` (part of the JDK). It asks for the passwords and
certificate details interactively, so nothing secret goes on the command line:

```bash
mkdir -p ~/secure/wavrecorder && chmod 700 ~/secure/wavrecorder
```

```bash
keytool -genkeypair -v -keystore ~/secure/wavrecorder/upload-keystore.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

The certificate details (name, organization, country) are visible to anyone
who inspects an APK. Use the publisher identity you want to be associated with.

After generating it, record the certificate's **SHA-256 fingerprint**. Play
Console shows it for comparison, and you'll need it to recognize the key later:

```bash
keytool -list -v -keystore ~/secure/wavrecorder/upload-keystore.jks -alias upload
```

## Build variants and what is signed

| Artifact | How it's produced | Signed with | Use |
|---|---|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `./gradlew :app:assembleDebug` | Gradle's local debug key | Development and side-loading. Never upload. |
| `app/build/outputs/bundle/release/app-release.aab` | `./gradlew :app:bundleRelease` | **Unsigned** | Diagnostic only: inspection, size checks. Never signed or uploaded. |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | `./gradlew :app:assembleRelease` | **Unsigned** | Diagnostic only. Can't be installed. |
| `dist/<release>/app-release-unsigned.aab` | `scripts/release.sh build` (below) | **Unsigned** | A verified release candidate, built from one commit. |
| `dist/<release>/<package>-<version>-vc<code>-upload-signed.aab` | `scripts/release.sh sign` (below) | Your **upload key** | The only artifact to upload to Play. |

The `release` build type deliberately has **no signing config**. Gradle never
signs a release build, and it never falls back to the debug key. Debug builds
and every unit test work without any production secret, including on a
working tree with uncommitted changes.

## Version codes

- `versionCode` (in `app/build.gradle.kts`) must increase with **every** upload
  to Play, on any track (internal, closed, open or production). Play
  permanently rejects a version code it has already seen for this app, even
  if that upload was deleted or never rolled out.
- Before the first upload (checked 2026-10-10): no tags, no GitHub releases,
  `versionCode` has been `1` since the first commit, and nothing has been
  uploaded to Play. So **1 is valid for the first upload**.
- After that, bump `versionCode` by 1 for each upload. Bump `versionName`
  (shown to users) as you like, for example `1.0` → `1.0.1` → `1.1`.
- Commit the bump. Releases are only ever built from a commit.

## Building and signing an upload

A release goes through `scripts/release.sh`. It never signs a bundle it didn't
build and verify itself.

1. **Build and verify one commit.** Name the commit (or a branch or tag that
   points at it) explicitly:

   ```bash
   scripts/release.sh build <commit>
   ```

   - **Isolated checkout.** It exports exactly that commit (`git archive`) into
     a fresh temporary directory, so uncommitted edits, untracked files and
     earlier build outputs can't reach the release.
   - **Gates.** It runs them all in that directory:
     `./gradlew --no-daemon --continue clean check lintRelease bundleRelease`
     (unit tests for debug and release, lint, the minified release bundle).
     If anything fails, it stops: nothing is kept or signed, and the isolated
     checkout and its log are left for inspection.
   - **Mapping.** It requires the R8 mapping embedded in the bundle
     (`BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map`) to
     be byte-for-byte identical to the mapping that build wrote, and archives
     that mapping.
   - **Release directory.** It keeps the verified outputs together in a new
     directory, `dist/<package>-<versionName>-vc<versionCode>-<commit>/`. It
     refuses if one already exists for that commit, and makes every file
     read-only:
     - `app-release-unsigned.aab` and `mapping.txt`;
     - `build.log`, the lint reports and the unit test results;
     - `build-record.txt`: the full commit and its tree, toolchain versions
       (Gradle, Android Gradle plugin, Kotlin, JDK, aapt2), test and lint
       results, package and version, SHA-256 of the bundle and the mapping,
       and whether owner placeholders are still in the bundle.

2. **Sign that release directory.** `jarsigner` asks for the keystore
   password, and the key password if it's different, at the prompt:

   ```bash
   scripts/release.sh sign dist/<release> ~/secure/wavrecorder/upload-keystore.jks upload
   ```

   `sign` takes only a release directory written by `build`, never a loose
   bundle. Before signing, it re-checks that:
   - the bundle and mapping still match the SHA-256 hashes in
     `build-record.txt`;
   - the recorded commit exists here with the recorded tree;
   - the bundle's embedded mapping is identical to the archived `mapping.txt`;
   - the bundle isn't already signed;
   - no `[[PLACEHOLDER: …]]` owner details are left;
   - the keystore lives outside the repository.

   It then signs, verifies the signature, checks the signed bundle still embeds
   the same mapping, and writes `sign-record.txt`: the upload certificate's
   SHA-256 fingerprint and the signed bundle's SHA-256. A directory is never
   signed twice.

3. Before uploading, check that the fingerprint `sign` prints matches the upload
   key certificate on Play Console's **App signing** page. That's after the
   first upload: for the first upload, Play records whichever upload key you
   use.

4. Upload `dist/<release>/…-upload-signed.aab` in Play Console.

`scripts/test-release-workflow.sh` exercises all of this against a disposable
copy of the repository and a disposable key, with three real builds (about 30
minutes):
- a stray bundle or an uncommitted edit can't reach a release;
- a swapped bundle, a relabelled commit, or a missing or mismatched mapping
  are refused;
- a failing test stops the release;
- a successful release archives exactly the built bundle and its mapping.

## Keep a release record for every upload

**Copy the whole `dist/<release>/` directory for each upload to durable
storage outside the repository**, such as an encrypted backup. `dist/` is
ignored by Git.

| Item | Why |
|---|---|
| Source commit and tree (full hashes) | Rebuild or audit exactly what shipped |
| Toolchain versions, test and lint results | Know how it was built and verified |
| `versionCode` / `versionName` | Match Play Console and crash reports to source |
| SHA-256 of the unsigned and signed bundles | Prove which file was uploaded |
| Upload certificate SHA-256 fingerprint | Confirm the right key was used |
| R8 `mapping.txt` (and its SHA-256) | Decode obfuscated stack traces |

The archived `mapping.txt` is the one embedded in that exact bundle, which Play
also uses for its own crash reports.

## Native code and 16 KB pages

Google Play requires apps that target Android 15+ to support 16 KB memory
pages if they ship native code. This app ships **no native libraries** (no
`lib/` directory in the APK or bundle), so there is nothing to align. Re-check
after adding any dependency:

```bash
unzip -l app/build/outputs/bundle/release/app-release.aab | grep '/lib/' || echo "no native libraries"
```
