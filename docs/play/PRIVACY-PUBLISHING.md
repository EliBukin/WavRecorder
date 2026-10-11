# Privacy page preparation and publication

## LIVE STATUS - supersedes the original preparation instructions below

Published and anonymously verified on 2026-10-11: https://elibukin.github.io/WavRecorder/privacy-policy.html

Pages now serves the dedicated gh-pages branch at its root with HTTPS enforced.
Published commit: 9476185f9d8bed22d837347e912180be5eeeb82d.
Only index.html, privacy-policy.html and .nojekyll were published. HTTP 200 and
byte-for-byte equality with the approved HTML were verified.
No application-source changes were committed, merged or pushed.
The previously prepared Actions workflow is NOT the active publishing route.
Future updates require owner approval, validation, then a normal non-force push
of only the policy files from an isolated checkout of gh-pages. Such a push
triggers publication. Do not run the old Actions workflow or change the Pages
source without an explicit migration decision. Play Console remains untouched.

## Historical preparation plan (not the current deployment procedure)

Prepared only; no Pages setting, branch, deployment or Play Console field has been changed.
The public `EliBukin/WavRecorder` repository had Pages disabled when checked on 2026-10-11.

## Approved details

- App: Bukin Mic Recorder; ID: `bukin.wavrecorder`.
- Public developer name: bukin.
- Support: dev.mechanics998877@gmail.com.
- Audience: general audience; not specifically designed for children.
  This does not select Play Console age groups or determine the content rating.
- Hosting plan: GitHub Pages. Only the privacy page is uploaded, not the whole repository.

## Approval status

The owner approved the wording and effective date **2026-10-11** on that date.
The policy is no longer a draft. Public deployment, commit/push and Play Console
changes have NOT been performed or authorized by this date-only update.
Steps 1-3 below are complete; remaining publication steps still require approval.

## Before publication

1. Owner reviews `docs/privacy-policy.md` and the generated HTML.
2. Obtain explicit publication approval and agree an effective date. Replace the date
   placeholder in the Markdown AND in-app `privacy_policy_html` with YYYY-MM-DD.
3. Remove the Markdown draft block. Regenerate with `python3 scripts/render-privacy-policy.py`.
   The renderer retains `noindex` while draft markers or placeholders remain.
4. Review the diff, build/test the app and approve the necessary commit/merge/push separately.
   Do not publish or merge the existing unreviewed recorder changes just to host this page.
5. Once approved files are on `master`, select Settings → Pages → Source: GitHub Actions.
6. Manually run **Publish approved privacy policy** on `master`, confirming the approval input.
   The workflow refuses unresolved placeholders/draft text or mismatched effective dates.
   No push/PR event publishes anything automatically.
7. Verify the deployed URL without signing in, including on mobile, and check contact/name/date.
   Only then replace the hosted-policy URL placeholders and enter it in Play Console.

Expected URL with GitHub's default project domain (not yet live/verified):
`https://elibukin.github.io/WavRecorder/privacy-policy.html`.

## Local preview and safety

`python3 scripts/prepare-privacy-site.py --preview` builds `_privacy_site/` for local review.
Without `--preview`, drafts are rejected. The output must not already exist: the script
refuses to overwrite it or sweep unrelated files into a deployment. Use a fresh disposable
checkout for repeated preview builds, or explicitly inspect/remove only that generated output.
The output is ignored by Git and contains exactly two copies of the policy (index and named
page) plus `.nojekyll`. There are no analytics, external fonts, scripts or sign-in requirements.
GitHub itself logs visitor IP addresses for security, disclosed in the policy's hosting section.

Run `python3 scripts/test-privacy-site.py` for local gate tests. These do not deploy anything.
The Actions workflow and public HTTP availability still require verification at publication.

References:
- https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages
- https://docs.github.com/en/pages/getting-started-with-github-pages/what-is-github-pages
