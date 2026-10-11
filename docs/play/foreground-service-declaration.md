# Foreground service declaration (draft for Play Console)

Play Console → **App content** → **Foreground service permissions**. The app
declares one foreground service type: **microphone**
(`android.permission.FOREGROUND_SERVICE_MICROPHONE`, `RecordingService`,
`android:foregroundServiceType="microphone"`, not exported).

Google's requirements for this form (checked 2026-10-10 against
[Understanding foreground service and full-screen intent requirements](https://support.google.com/googleplay/android-developer/answer/13392821)):

- a description of the feature that uses each type;
- the user impact if the system **defers** the task, and if it **interrupts**
  it (pauses or restarts it);
- a link to a video showing the steps the user takes to trigger the feature;
- a use case. For microphone, the preset is **Background Audio Access**
  ("Capture audio input, for example … voice recording").

## Draft answers

**Foreground service type:** Microphone

**Use case:** Background Audio Access (voice/audio recording)

**Description of the feature**

> The app is an audio recorder. Recording starts only when the user taps
> "Start recording" in the app. The app then starts a foreground service of
> type microphone. It captures audio from the microphone the user connected
> (USB or built-in) and writes it to a WAV file. While the service runs, an
> ongoing notification shows that recording is in progress and offers a Stop
> action. Recording continues when the user switches to another app or turns
> the screen off, so long recordings (lectures, rehearsals, interviews) are not
> cut short. It ends when the user taps Stop (in the app or in the
> notification), or when the microphone is disconnected, storage fails or the
> microphone stops delivering audio. The service never starts on its own (no
> boot or background start) and stops itself when the recording ends.

**User impact if the task is deferred**

> Recording must start the moment the user taps Start: the audio being
> captured is live and cannot be recaptured later. If the system deferred the
> start, the beginning of the user's recording would be lost.

**User impact if the task is interrupted**

> Interrupting the service would stop capturing live audio. Everything from the
> interruption until the user notices would be permanently lost, and the
> current WAV file would be left unfinalized. The app can repair the file on
> its next start, but it cannot recover the missing audio.

**Video link:** [[PLACEHOLDER: VIDEO URL, unlisted, viewable without sign-in]]

## Demonstration video script (about 60–90 seconds)

Record the screen of a **test device or emulator with no personal recordings**.
Use a silent room or a test tone. Don't capture anyone's voice or private
audio, and don't show other apps' private content or notifications. Turn on
Do Not Disturb or hide unrelated notifications first.

1. **Open the app** on the Record screen (5 s). Show the Input device card,
   e.g. "Phone mic" or the connected USB microphone.
2. **Grant permissions if asked** (10 s). Tap **Start recording**. If Android
   asks, allow microphone access and notifications.
3. **Recording in progress** (10 s). Show the elapsed timer counting and the
   waveform moving with the test tone. Show the "Recording" state.
4. **Background operation** (10 s). Press Home and open another app, such as
   Settings. Pull down the notification shade: the ongoing "Recording"
   notification with its **Stop** action is visible.
5. **Screen off** (10 s). Turn the screen off for a few seconds, then turn it
   on and unlock. The notification is still there and the timer has kept
   counting.
6. **Stop** (10 s). Tap **Stop** in the notification, or return to the app and
   tap **Stop & save**. The notification disappears and the app shows
   "Recording saved".
7. **Result** (10 s). Open **Library**. Show the new recording and play a few
   seconds of the test tone.

Upload the video as **unlisted** (for example on YouTube), check that it plays
without signing in, and paste the link above.

## Consistency checks before submitting

- The declaration matches the merged release manifest: exactly one
  foreground service type (`microphone`), and no other `FOREGROUND_SERVICE_*`
  permissions.
- The description matches what the video shows.
- The privacy policy and Data safety answers describe the same microphone use.
