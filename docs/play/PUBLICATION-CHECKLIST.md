# Google Play publication checklist

Status as of 2026-10-11, branch `play-store-preparation`. **The app is not ready
to publish.** The owner decisions and blockers below are still open. No app bundle has been uploaded by this workflow. The owner has opened a
personal developer account and reports identity verification is pending.

## 1. Owner decisions (blockers)

| # | Decision | Status | Notes |
|---|---|---|---|
| 1 | **Permanent application ID** | **Approved: `bukin.wavrecorder`** | Owner approved on 2026-10-11. Namespace and code packages match. Play availability is not yet verified. |
| 2 | **Store app name** | **Approved: Bukin Mic Recorder** | Launcher label, store draft and privacy policy updated. Play review and third-party rights are not verified. |
| 3 | **Developer / publisher name** | **Approved: bukin** | Public developer display name, as confirmed by owner; does not replace legal identity verification details. |
| 4 | **Support email** | **Approved: dev.mechanics998877@gmail.com** | Public support contact; owner-approved. |
| 5 | **Privacy policy hosting URL** | **Published and verified** | https://elibukin.github.io/WavRecorder/privacy-policy.html - HTTPS, gh-pages branch. See PRIVACY-PUBLISHING.md. |
| 6 | **Audience statement** | **Approved: general audience, not specifically designed for children** | App and policy updated. Play target age groups and content rating still need separate completion. |
| 7 | **Upload key** | Open | The owner generates and keeps it, following `docs/RELEASING.md`. No key has been generated or chosen. |
| 8 | **Account type** | Open | A personal account created after 13 Nov 2023 must run a **closed test with at least 12 testers opted in for 14 continuous days** before applying for production. The checked help article doesn't say whether organization accounts are exempt; confirm in Play Console. |
| 9 | **Library playback in the background** | **Decided:** stop when the app leaves the foreground | Implemented on this branch. No background playback service. |
| 10 | **License for the app's own code** | Optional | The repo has no license, so all rights are reserved by default. Not needed for Play. |

Every `[[PLACEHOLDER: …]]` must be resolved in **both**
`docs/privacy-policy.md` / `.html` and `privacy_policy_html` in
`app/src/main/res/values/strings.xml`. `scripts/release.sh sign` refuses to
sign a bundle that still contains one.

### Application ID

Approved by the owner on 2026-10-11: **`bukin.wavrecorder`** for both
`applicationId` and `namespace`, with matching Kotlin packages and source paths.
This replaces the old example identity. No debug suffix was added, and the
visible app name is unchanged. Availability on Google Play remains unverified.

The owner explicitly requested removal of the old A55 installation and all
18 recordings in its selected `Micrecordings` folder; this was completed before
the rename. This is a historical action, not permission to delete future data.
No new build has been installed as part of the identity change.

Changing application ID creates a different Android app. For any other device
still holding an older installation, preserve recordings before uninstalling it.
Future debug-to-Play transitions also require checking signing compatibility.

### Library playback

Decided 2026-10-11: playback **stops when the app leaves the foreground**: Home,
another app, the screen turning off. The player and its audio focus are
released, and the list shows the track stopped. Nothing resumes on return.
Switching between the Record and Library pages doesn't stop playback. A
configuration change such as rotation ends it, as it did before. Recording and
Mic Test are unaffected.

Background playback with a media session and a `mediaPlayback` foreground
service was **not** added. That would be a new feature, with a new permission
and a new Play declaration.

## 2. Store presence (Play Console)

- [ ] App name, short and full description: draft in `store-listing.md`.
- [ ] App icon 512×512 PNG: **prepared**, awaiting owner review/upload; see assets/play-icon-512.png.
- [ ] Feature graphic 1024×500: **draft prepared**, awaiting owner approval; see assets/.
- [ ] Phone screenshots: **five emulator drafts prepared** from a minified release build signed for testing only. A real A55 Mic Test source is also saved; it needs store-format preparation. Active recording still needs a real-device replacement. See assets/CAPTURE-NOTES.md.
- [ ] Category (Music & Audio suggested), contact email, privacy policy URL.

## 3. App content declarations (Play Console)

- [x] **Privacy policy** URL hosted, verified and entered in Play Console.
- [ ] **Data safety**: "no data collected or shared". Draft in
      `data-safety.md`.
- [ ] **Ads**: no ads.
- [ ] **App access**: everything is available without login.
- [ ] **Target audience and content**: owner decision #6.
- [ ] **Content rating** questionnaire: a utility/recorder with no
      user-generated sharing.
- [ ] **Foreground service permissions**: microphone. Draft and video script in
      `foreground-service-declaration.md`. The video still has to be recorded.
- [ ] Government, financial, health and news declarations: not applicable;
      answer the questions as Play asks.

## 4. Testing on Play

- [ ] Create the app with the final application ID and name.
- [ ] **Internal testing**: upload the first signed bundle (version code 1)
      and install it from Play on test devices. This is the first time the
      Play-signed, minified build runs on real hardware.
- [ ] **Closed testing**: 12+ testers for 14 continuous days, if the account
      is a personal one created after 13 Nov 2023.
- [ ] Review the **pre-launch report** (Play's automated device tests) and
      Android vitals.
- [ ] Production: apply for access when eligible, then roll out (a staged
      rollout is recommended).

## 5. Technical preparation done on this branch

- Target and compile SDK **36** (Play requirement since 31 Aug 2026). Minimum
  SDK still **24**.
- Toolchain:
  - AGP 8.13.2, Gradle 8.14.6 (wrapper checksum pinned), Kotlin 2.3.10,
    Robolectric 4.16.1.
  - Chosen as the newest mutually compatible set without the AGP 9 migration:
    AGP 8.13 supports up to API 36.1 and needs Gradle ≥ 8.13; Kotlin 2.3.10
    supports AGP ≤ 9.0 and Gradle ≤ 9.0; Robolectric 4.16 is the first
    release with SDK 36.
  - AndroidX and Material versions are unchanged. None needed updating:
    `enableEdgeToEdge` comes from activity 1.8.1, already resolved.
- **Edge-to-edge** with explicit insets (header, sides, bottom navigation). No
  opt-out.
- **Backup**: an allowlist of the split-length preference only, for Android
  6–11 and 12+ (cloud backup and device transfer).
- **About & privacy** dialog (info button in the header) with the policy text
  and open-source licenses.
- **Release workflow**: Gradle never signs release builds. `scripts/release.sh`
  builds and verifies one commit in an isolated checkout, then signs only that
  verified bundle with its embedded R8 mapping archived. See
  `docs/RELEASING.md`.
- **Library playback** stops when the app leaves the foreground.
- No native code, so the 16 KB page-size requirement doesn't apply. Re-check
  after any dependency change.
- Permissions unchanged: microphone, foreground service (microphone),
  notifications. Nothing added.

## 6. Before every upload

1. Bump `versionCode` (never reuse one) and commit.
2. Run `scripts/release.sh build <commit>`, then `scripts/release.sh sign`.
   Check the certificate fingerprint and archive the release directory.
3. Upload to the internal track first.
