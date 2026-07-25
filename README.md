# WavRecorder

A native Android app for recording uncompressed WAV audio, built to get the best
possible quality out of an external microphone (developed and tested with the
Insta360 Mic Air), with a library for browsing and playing back past recordings.

## Features

- **Record** raw 16-bit PCM WAV audio with a live waveform display.
- **Background-safe recording**: recording runs in a foreground service, so it
  keeps going with the screen off or the app switched away, with a persistent
  notification (with a Stop action) while it's active.
- **Auto-split**: recordings longer than 60 minutes automatically roll over into
  a new file, continuing seamlessly, named `recording_<timestamp>_partNN.wav`.
- **Best available capture quality**: prefers `AudioSource.UNPROCESSED` (raw mic
  signal, no platform AGC/noise suppression/echo cancellation) and 48kHz sampling,
  falling back gracefully on devices that don't support either.
- **Configurable destination**: record straight to app-private storage, or pick
  any folder via the system folder picker (Storage Access Framework).
- **Library tab**: browse past recordings with duration/size, play them back with
  a real seek bar, skip-free scrubbing, variable playback speed (1x/1.25x/1.5x/2x),
  and delete recordings you don't need.
- **Playback behaves like a real media app**: audio focus handling (pauses for
  calls/other apps), and auto-pause when headphones are unplugged.
- **Geek stats**: per-recording dialog with sample rate, channels, bit depth,
  bitrate, peak/RMS level in dBFS, and clipped-sample count.

## Requirements

- Android 7.0 (API 24) or newer.
- Microphone permission (requested at first recording). Android 13+ also
  requests notification permission, needed to show the recording-in-progress
  notification.

## Project structure

```
app/src/main/java/com/example/wavrecorder/
  MainActivity.kt            Hosts the Record/Library tabs (ViewPager2 + TabLayout)
  RecordFragment.kt          Record tab UI; binds to RecordingService, has no
                              recording logic of its own
  RecordingService.kt        Foreground service that owns the WavRecorder instance,
                              the notification, and the recording lifecycle
  WavRecorder.kt              Core capture engine: AudioRecord setup/quality
                              selection, the record loop, and hand-rolled WAV
                              header read/write
  LibraryFragment.kt          Library tab: lists recordings, drives MediaPlayer
                              playback (focus, speed, seeking, noisy-receiver)
  RecordingsAdapter.kt        RecyclerView adapter for the recordings list
  DestinationManager.kt       Resolves where recordings live: app storage vs. a
                              user-picked SAF folder; list/create/delete files
  OutputTarget.kt              Abstraction over a plain File vs. a SAF Uri target
  WavFileInfo.kt               Cheap header-only read for duration/size
  AudioStats.kt                Full-file scan for the stats dialog (peak/RMS/clipping)
  RecordingNameFormatter.kt    Turns a recording filename into a human-readable title
  WaveformView.kt              Custom View: smoothed live waveform during recording
```

## Building

No Android Studio required — this was built entirely from the command line with
the Android SDK command-line tools and JDK 17.

```bash
export JAVA_HOME="/path/to/jdk-17"
export ANDROID_HOME="/path/to/android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
./gradlew.bat assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. It's
signed with Gradle's default debug keystore, so it installs like any normal app
(no `adb` required — just copy it to the phone and open it, allowing "install
from unknown sources" if prompted).

## Why a hand-rolled WAV writer

`AudioRecord` gives raw PCM samples with no container format. There's no WAV
muxer in the Android SDK, so `WavRecorder` writes the 44-byte RIFF/WAVE header
itself, patches its size fields in as recording progresses (so a mid-recording
crash or kill still leaves a playable file), and finalizes it on stop or on
each 60-minute segment rollover.
