# Data safety form (draft answers)

Play Console → **App content** → **Data safety**. Answers based on the code at
the time of writing. Re-check them if the app gains internet access, an SDK, a
share feature or any other way to send data off the device.

Google's definitions (checked 2026-10-10, [Provide information for Google Play's Data safety section](https://support.google.com/googleplay/android-developer/answer/10787469)):

- **Collected** means "transmitting data from your app off a user's device".
  Data processed only on the device doesn't need to be disclosed.
- Data the user sends to **their own** cloud storage, under that provider's
  terms, without the app collecting or accessing it, doesn't need to be
  declared.
- Apps that collect and share nothing must still complete the form and link a
  privacy policy.

## Why the answer is "no data collected or shared"

| Fact (from the code and the merged release manifest) | Effect |
|---|---|
| No `INTERNET` permission. No networking, analytics, ads or crash-reporting code or SDKs. | The app can't transmit anything off the device itself. |
| Recordings are written to app storage, or to a folder the user picks through Android's folder picker. | If that folder belongs to a cloud provider, the user sends the files to their own account under the provider's terms. Not collection by the app. |
| Share and Save a copy (since 1.1) only hand a recording to Android's share sheet or save dialog when the user taps them; the user picks the receiving app or place. The app gains no network access and sends nothing itself. | User-initiated transfer to an app or place the user chooses, not collection or sharing by the app. Re-checked for 1.1 against the definitions above; confirm in Play Console before submitting. |
| Mic Test audio is processed in memory and never saved or sent. | Processed on the device only. |
| Android's backup includes only the split-length preference (`backup_rules.xml`, `data_extraction_rules.xml`). | Not app-initiated transmission. The privacy policy describes it anyway. |

## Answers

1. **Does your app collect or share any of the required user data types?**
   **No.**
2. Security practices and deletion questions: not applicable when nothing is
   collected. Play hides them after "No".
3. **Privacy policy URL:** https://elibukin.github.io/WavRecorder/privacy-policy.html. Required
   even with "No".

## Related declarations to keep consistent

- **Privacy policy** (`docs/privacy-policy.md`, and the in-app copy in
  `privacy_policy_html`) says the same thing in plain language.
- **Ads:** the app contains no ads. Answer "No".
- **App access:** nothing is restricted. No login or account, so no
  credentials are needed for review.
- **Foreground service:** see `foreground-service-declaration.md`.
- **Permissions shown on the store page** (from the manifest): microphone,
  foreground service (microphone) and notifications.
