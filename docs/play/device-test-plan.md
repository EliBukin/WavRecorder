# Real-device test plan (protects existing recordings)

The emulator and unit tests cover a lot, but not real microphones, real
power management or Play's signed build. This plan is for the Galaxy A55 and
any other test phone. It's written so that **existing recordings on the A55
are never at risk**.

## Ground rules for the A55

- **Never** uninstall the existing app, clear its data, run
  `adb shell bmgr restore`, or install a build signed with a different key
  over it.
  - Android **deletes all of an app's data, including recordings in its app
    storage, before applying a full backup restore**. The emulator log showed
    "Clearing app data preparatory to full restore".
  - A build with a different signature can only be installed after an
    uninstall, which deletes the same data.
- **Back up first.** Before installing anything new, copy the recordings off
  the phone and compare checksums. As the shell user:

  ```bash
  adb -s <A55-serial> pull /sdcard/Android/data/bukin.wavrecorder/files ./a55-recordings-backup
  ```

  (Then compare `sha256sum` of each file against
  `adb shell sha256sum /sdcard/Android/data/bukin.wavrecorder/files/*`.)
- The owner-approved ID is `bukin.wavrecorder`. The old A55 app and its
  recordings were explicitly removed at the owner's request before this rename.
  No new installation is authorized by that removal. On subsequent installs,
  check the current package and signing key first; never assume the device is
  still empty. Debug and Play builds currently share this ID and may have
  different signatures. Do not uninstall either without approval and a data plan.
- Use test recordings with neutral names. Don't record private conversations.

## What still needs a real device

| Area | Steps | Pass criteria |
|---|---|---|
| Edge-to-edge on the A55 | Gesture and 3-button navigation, light and dark, portrait and landscape, largest font size | Header below the status bar and cutout. Bottom navigation clear of the navigation bar. No clipped buttons. |
| USB microphone (Insta360) | Mic Test, record 2–5 min, Library playback | Correct device name, "Verified" badge, valid WAV |
| Background and screen off | Start recording, Home, screen off 5+ min, unlock | Notification with Stop throughout. File grows. Stop from the notification saves. |
| **Unplug the microphone while recording** | Pull the USB mic | Recording stops with the documented message. File saved up to that point. |
| **Incoming phone call while recording** | Call the phone | The app keeps running. Note what the audio contains during the call (the platform may silence the mic). |
| **Forced termination** | During a test recording: Settings → Apps → the app → Force stop | On next launch, crash recovery repairs the file, and the Library plays it. |
| **Storage failure** | Pick an SD card or USB-storage folder and remove it during a test recording | Explicit error. Audio up to the failure is kept, or flagged for recovery. |
| Notification permission denied | Deny notifications, start recording | Warning dialog. Recording runs. No shade notification. |
| Library playback | Play, then press Home or turn the screen off. Return. Repeat while a recording runs. | Playback stops within about a second. The track shows stopped on return and doesn't resume. A recording in progress keeps running. Switching to the Record page alone doesn't stop playback. |
| Back gesture | Library → Select → back swipe | Leaves selection mode. Next back leaves the app. |
| About & privacy | Header info button, rotate, Open-source licenses | Readable, scrolls, survives rotation |

## Play-signed build

Install from the **internal testing** track, which is a Play-signed, minified
build. Repeat at least: record and stop, background/screen-off, Library
playback, About & privacy. Then check Play Console's **pre-launch report** and
**Android vitals** for crashes.

## Already verified (not on a real device)

On an Android 16 (API 36) Google APIs **emulator**, with the debug build and a
locally test-signed copy of the minified release build:

- edge-to-edge in gesture and 3-button navigation, light and dark, landscape
  with an emulated cutout, 200% font, a 320dp-wide window and a tablet-sized
  window;
- permission prompts in order (app's phone-mic confirmation, microphone,
  notifications);
- the foreground service started with type microphone;
- recording continued in the background and with the screen off;
- Stop from the notification saved a valid WAV;
- Back and back-swipe leave Library selection mode;
- Mic Test released the microphone on Home;
- Library playback:
  - stopped and released audio focus on Home and on screen-off;
  - showed the track stopped on return, without resuming;
  - continued while switching to the Record page;
  - when started during a recording, stopped on Home while the recording kept
    running at full rate;
- backup and device-transfer archives held only `wav_recorder_settings.xml`;
- reinstall auto-restore brought back only that file;
- the About & privacy and licenses dialogs worked in the minified build and
  survived rotation.
