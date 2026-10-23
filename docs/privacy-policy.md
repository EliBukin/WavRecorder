# Privacy policy

- **App:** Bukin Mic Recorder
- **Developer:** bukin
- **Contact:** dev.mechanics998877@gmail.com
- **Effective date:** 2026-10-23

This policy explains what the app does with your information. The app is a
microphone recorder that saves uncompressed WAV files. It works with USB and
built-in microphones. It is not a phone-call recorder.

## Summary

- The app records audio only when you start a recording or a Mic Test.
- Recordings are saved as WAV files in the place you choose: the app's own
  storage on your device, or a folder you pick.
- The app has no internet permission, no ads, no analytics and no accounts. It
  does not send your recordings or other information to the developer or to
  anyone else.
- Recordings leave your phone through this app only when you use **Share** or
  **Save a copy**, or when you pick a cloud-backed folder to save them in.
  After that, or if you copy files yourself with another app, what happens to
  them is outside the app's control.

## Microphone

The app asks for the microphone permission. It uses the microphone only:

- **While you record.** Audio from the microphone is written to a WAV file.
- **During a Mic Test.** The app reads audio to show a live level meter. Mic
  Test audio is processed in memory and is **not** saved to a file. The test
  stops when you leave its screen.
- **Briefly when a recording or Mic Test starts**, to find a recording format
  your microphone and phone support.

The app never starts recording on its own.

## Recording in the background

When you start a recording, it keeps running if you switch to another app or
turn the screen off. While it runs, Android shows an ongoing notification with
a **Stop** button, and the recording continues until you stop it, an error
occurs (for example, the microphone is disconnected or storage is full), or
Android ends the app.

On Android 13 and later the app also asks for permission to show
notifications, so that the recording notification can appear. If notifications
are turned off, the app warns you before recording. Recording still runs
normally in the background, but the notification and its Stop button are not
shown in the notification shade. You can still stop the recording in the app.

## Where recordings are stored

You choose where recordings are saved:

- **App storage (default).** Files are saved in the app's own folder on your
  device's shared storage (`Android/data/bukin.wavrecorder/files`).
  Android deletes this folder when you uninstall the app, so copy any
  recordings you want to keep before uninstalling. On Android 10 and earlier,
  other apps with storage permission may be able to read files in this folder.
- **A folder you pick.** If you choose a folder with Android's folder picker,
  recordings are saved there. That folder may belong to another app or service,
  such as a cloud storage app. That service may copy, sync or back up your
  recordings under its own terms and privacy policy. Files in a picked folder
  are not deleted when you uninstall this app.

The app does not encrypt WAV files. They are protected by Android's normal
storage protections and by the folder or service you chose.

## How long recordings are kept, and deleting them

Recordings are kept until you delete them. You can delete recordings
individually or in bulk in the app's Library. You can also delete them with a
file manager or in the folder you picked. A file that is still being recorded
or repaired cannot be deleted from the Library until it is finished.

## Crash recovery and other app data

To protect a recording if the app or phone stops unexpectedly, the app keeps a
small private record of the file it is currently writing: the file's name and
location, and its recording format. After an interruption, the app uses this
record to repair the file so the audio already captured can be played. The
record is removed once the file is finished or repaired.

The app also stores, privately on your device:

- your chosen file-splitting interval;
- the folder you picked, if any;
- a short note about how the last recording ended, so the app can tell you
  after it restarts;
- whether you have seen the "What's new" note shown after an update.

This information stays on your device. The app may also write technical
diagnostic messages to Android's system log on your device, such as file names,
the microphone's name and the recording format. The app does not send these
messages anywhere.

## Backups and moving to a new phone

Android can back up app data to your Google account and copy it to a new phone.
For this app, Android's backup and device-transfer features include **only the
file-splitting interval**. They exclude recordings, the folder you picked,
crash-recovery records and other device-specific data.

These rules apply only to Android's own backup and transfer features. They do
not control separate backup, sync or file-transfer tools, including a cloud
storage app that holds a folder you picked.

## Information shared with the developer or third parties

The app has no internet permission and contains no advertising, analytics,
tracking or crash-reporting code. The app itself does not collect, sell or
share your personal information or recordings.

If you install the app from Google Play, Google provides the developer with
statistics such as install counts and, if you agreed to share usage and
diagnostics data with Google, crash and performance reports about the app.
Google processes this data under its own privacy policy.

If you use **Send feedback**, the app opens your email app with a message to
the developer that includes the app version, Android version and phone model.
Nothing is sent unless you send it yourself, and the developer uses your
message only to reply and to improve the app.

## Children

The app is intended for a general audience and is not specifically designed for children.

## Visiting this policy webpage

This privacy-policy webpage is hosted on GitHub Pages. GitHub logs visitor IP addresses for security purposes. This is separate from the app, which does not upload recordings or include analytics. See the [GitHub Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement).

## Changes to this policy

If this policy changes, the updated version will be published at this address
with a new effective date.

## Contact

Questions about this policy: dev.mechanics998877@gmail.com
