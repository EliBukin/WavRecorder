# WavRecorder

A native Android app for recording uncompressed WAV audio, built to get the best
possible quality out of an external microphone (developed and tested with the
Insta360 Mic Air), with a library for browsing and playing back past recordings.

## Features

- **Record** uncompressed PCM WAV audio with a live waveform display, in the
  highest-quality lossless format it can find for the selected microphone,
  judged by what Android reports it captures (see
  [Recording format](#recording-format)).
- **Background-safe recording**: recording runs in a foreground service, so it
  keeps going with the screen off or the app switched away, with a persistent
  notification (with a Stop action) while it's active.
- **Auto-split**: long recordings automatically roll over into a new file,
  continuing seamlessly, named `recording_<timestamp>_partNN.wav`. The split
  length is selectable on the Record screen (30, 45 or 60 minutes; 60 by
  default), remembered across app restarts, and fixed for a session once it
  starts: a change takes effect from the next recording.
- **Best available capture quality**: negotiates the format automatically, and
  for each format tries `AudioSource.UNPROCESSED` (meant to skip platform
  AGC/noise suppression/echo cancellation, where the platform supports it)
  before `MIC`; the Record screen shows the format actually being saved, e.g.
  `Insta360 Mic Air • 48 kHz • 16-bit • Mono`.
- **Configurable destination**: record straight to app-private storage, or pick
  any folder via the system folder picker (Storage Access Framework).
- **Library tab**: browse past recordings with duration/size, play them back with
  a real seek bar, skip-free scrubbing, variable playback speed (1x/1.25x/1.5x/2x),
  and delete recordings you don't need.
- **Playback behaves like a real media app**: audio focus handling (pauses for
  calls/other apps), and auto-pause when headphones are unplugged.
- **Geek stats**: per-recording dialog with sample rate, channels, bit depth,
  bitrate, peak/RMS level in dBFS, and clipped-sample count -- for 16/24/32-bit
  integer and 32-bit float files alike.

## Recording format

The format is negotiated once per recording (and per microphone test, through
the same code), only when it starts, and never changes within a session. It is
chosen by what Android reports it *captures* from the input device -- the
device side, `AudioRecordingConfiguration.getFormat()` -- not merely by the
format `AudioRecord` agrees to deliver (`getClientFormat()`), which Android may
produce by resampling or converting.

**Format evidence** -- what Android reports about the device side:

- **Matched** -- the device side is exactly the saved format: Android reports
  no rate, sample-format or channel conversion between its capture stream and
  the app.
- **Converted** -- the device side differs: Android converts to produce the
  saved format.
- **Unreported** -- Android doesn't report the device side for this capture.

A matched format is evidence about Android's capture path, not proof of the
microphone's fidelity: it doesn't show the converter's effective resolution,
that no processing ran inside the device or its driver, or bit-perfect capture.

**Selection policy** (defined once, in `CaptureQuality`) -- an explicit, tested
policy for choosing among formats, not a universal ranking of how they sound.
Highest first:

1. Full band -- at least 44.1 kHz -- beats any telephony rate, whatever the
   sample format: 24-bit/192 kHz beats 32-bit/8 kHz.
2. A sample format of at least 24 bits beats 16-bit.
3. Then the higher sample rate.
4. Then the sample format: 32-bit integer, 32-bit float, 24-bit, 16-bit. All
   three wide formats hold 24-bit audio losslessly; this order is a policy
   choice, not a claim about converter resolution.
5. Then more channels.

A candidate is credited only with what the evidence supports:

- Matched: its format.
- Converted: the lower rate, sample format and channel count of the two.
  Upsampling, padded bits and duplicated channels add nothing, so a
  32-bit/192 kHz stream carried at 24-bit/48 kHz counts as 24-bit/48 kHz.
- Unreported, for a format the device advertised: its format, with 32-bit and
  float counted only as 24-bit.
- Unreported, for an exploratory format (below): no more than the long-standing
  48 kHz 16-bit mono -- that `AudioRecord` accepted it shows only that Android
  can deliver it.

At an equal credited level: matched beats unreported beats converted; then
`UNPROCESSED` beats `MIC`; then an advertised format beats an exploratory one;
then the smaller stream (the least uncredited padding or resampling -- a bigger
container is never assumed to sound better); then the order candidates were
tried in. A higher credited level still beats a less-processed source: the level
only counts what the device side carries, while platform processing can't be
observed from the app either way.

**Candidates.** The selected microphone -- an attached external input,
preferring an Insta360 accessory, otherwise the built-in mic -- reports its
capabilities: correlated `AudioProfile`s on Android 12+ (API 31), the older
independent capability arrays before that. Each dimension (encoding, sample
rate, channels) is one of:

- *Advertised*: used exactly as advertised, however high.
- *Unspecified* (nothing listed): the long-standing 16-bit, 48/44.1 kHz and mono
  values, plus a small exploratory set -- one high-precision encoding (24-bit on
  Android 12+, 32-bit float before), 96 and 192 kHz, and stereo. A device that
  reports nothing at all gets 14 exploratory formats beyond the two
  compatibility ones.
- *Unsupported* (listed, but nothing the app can record -- only compressed
  encodings, out-of-range rates, too many channels): nothing is invented from
  it.

The 48 and 44.1 kHz 16-bit mono formats are always offered too. Formats outside
the exploratory set (88.2/176.4 kHz, 32-bit integer, more channels) are reached
only through a device-side report, and no finite search can prove the maximum of
capabilities a device doesn't describe.

**Negotiation:**

1. Candidates are tried best possible first, each with `UNPROCESSED`, then
   `MIC`, before the next format (`FormatNegotiation.attemptOrder`). `MIC` may
   apply platform processing; the diagnostics say which source was used and
   whether the platform declares unprocessed capture supported.
2. Every candidate must pass every step: `AudioRecord` initializes with exactly
   the requested rate, encoding and channel count; the selected microphone is
   requested with `setPreferredDevice` (a refusal rejects it); it starts
   recording; within its own window of up to 500 ms, the device it is actually
   routed to (`getRoutedDevice()`) is the selected external microphone; then its
   device-side format is read.
3. The search continues while an untried candidate could still score higher than
   the best so far; a converted result tries the exact device-side format next.
   Missing device-side reports never rule a candidate out -- a later one may
   report. After three in a row (and none reported), the candidate that can win
   without a report (usually the compatibility format) is simply tried next, so a
   usable result is in hand early; the search then carries on within its budget.
4. Once a candidate is usable, further ones may be started for 3 seconds (one
   costs roughly 150-400 ms on a USB microphone, so this covers some 8-20); the
   whole negotiation is limited to 8 seconds, 2 of them kept for the
   phone-microphone fallback and 0.8 for reopening the winner.
5. Only one candidate is ever open. If the winner had to be stopped to compare,
   it is reopened and every check in step 2 is repeated from scratch. A reopen
   counts as a fresh observation: if the winner now captures less, the ranking
   and the search (within its budget) continue from what it captures now; if it
   fails, the next best is reopened instead. A candidate that reopened at lower
   quality is kept as a fallback and may be reopened once more if the
   alternative fails -- each candidate is reopened at most twice, and nothing
   runs past the 8-second limit. If time runs out while a lower candidate is
   still running, it is used rather than lost.
6. If an external microphone is attached but no format can be verified on it,
   the phone's microphone is negotiated the same way; a recording started while
   the external microphone was expected is then refused before anything is
   recorded, and the Record screen offers to retry or continue with the phone.

The search reports its **coverage** separately from the format evidence:
*complete* (every candidate that could have improved on the result was tried),
*incomplete* (it stopped first -- the time budget or the deadline -- or a
candidate that scored higher couldn't be reopened) or *none usable*. It is
judged against what the returned capture scores when it is handed over, not
against earlier observations. An incomplete search's result is the best format
found, never described as the maximum.

Everything that touches the microphone natively -- negotiation, and stopping
and releasing a microphone -- runs on one background thread shared by recording
and the microphone test, never the main thread: a stalled audio driver can't
freeze the screen, and a new start can't open a microphone until the previous
one has been released. While a start is negotiating, the Record screen shows
"Preparing to record…" and Stop cancels it; a cancelled start never becomes a
recording, and no file is created until the microphone has been accepted.

That thread can't be interrupted, so a **watchdog** on the main thread supervises
it. A start that hasn't finished within 12 seconds -- queued time included -- ends
with a clear error, exactly once; anything it opens later is released instead of
used. While a stop or release (or an abandoned start) has been running for over
2 seconds, new starts are refused at once ("Android is still releasing the
microphone") rather than queued. A timeout only means the app stopped waiting:
nothing new is opened until the stuck call returns, and then everything works
again. A recording's file status and the microphone's release are reported
separately: a finalized file shows as saved even while Android is still
releasing the microphone, with a note until it has.

Audio is read with `AudioRecord.read(ByteBuffer, ...)` into a reused direct
buffer -- the one read that works for every encoding, float included -- and
saved as little-endian samples, exactly as delivered. The WAV header always
describes those bytes (the client format). The Record screen shows the saved
format and, only when useful, a short note: that Android converts it (naming the
device-side format), that Android doesn't report whether it converts, or that
the search for a higher-quality format stopped early. The full evidence -- the
advertised capabilities, every candidate tried, what Android reported for each,
why the search ended and how long it took -- is logged under `AudioNegotiation`.

WAV files use the plain PCM header for 16-bit mono/stereo, `WAVE_FORMAT_IEEE_FLOAT`
for float, and `WAVE_FORMAT_EXTENSIBLE` for 24/32-bit and multichannel audio.
Classic WAV files can't exceed 4 GiB, so a format that would reach that before the
chosen split interval (e.g. 192 kHz / 32-bit stereo, after ~46 minutes) starts a
new file early; the Record screen says so.

Approximate storage per hour: 48 kHz 16-bit mono 0.35 GB; 48 kHz 16-bit stereo
0.69 GB; 48 kHz 24-bit stereo 1.04 GB; 96 kHz 24-bit stereo 2.07 GB; 192 kHz
32-bit stereo 5.53 GB (split into ~46-minute files).

## Requirements

- Android 7.0 (API 24) or newer.
- Microphone permission (requested at first recording). Android 13+ also
  requests notification permission, needed to show the recording-in-progress
  notification.

## Project structure

```
app/src/main/java/com/example/wavrecorder/
  MainActivity.kt            Hosts the Record/Library pages (ViewPager2 + bottom navigation)
  RecordFragment.kt          Record tab UI; binds to RecordingService, has no
                              recording logic of its own
  RecordingService.kt        Foreground service that owns the WavRecorder instance,
                              the notification, and the recording lifecycle
  WavRecorder.kt              Core capture engine: opening the negotiated
                              AudioRecord, the record loop, segment rollover
  FormatNegotiation.kt        Pure candidate ranking/fallback for the input format
  CaptureQuality.kt           The selection policy and format evidence (matched / converted / unreported)
  CaptureNegotiation.kt       Route-aware candidate lifecycle and the coverage-aware quality search
  AudioStartup.kt             The audio worker and its watchdog: startup, handoff,
                              cancellation, shutdown, timeouts
  AudioInputCapabilities.kt   Reads a device's capabilities for negotiation
  PcmReader.kt                 The direct-buffer read path shared by recording and mic test
  PcmFormat.kt                 The immutable session format (rate, encoding, channels)
  PcmSamples.kt                Sample decoding for levels and statistics
  SegmentSplitPlan.kt          Split duration vs. the 4 GiB WAV limit
  WavHeaderWriter.kt           WAV headers for every supported format
  WavRiffParser.kt             Chunk-walking WAV reader (any header size)
  LibraryFragment.kt          Library tab: lists recordings, drives MediaPlayer
                              playback (focus, speed, seeking, noisy-receiver)
  RecordingsAdapter.kt        RecyclerView adapter for the recordings list
  DestinationManager.kt       Resolves where recordings live: app storage vs. a
                              user-picked SAF folder; list/create/delete files
  OutputTarget.kt              Abstraction over a plain File vs. a SAF Uri target
  WavFileInfo.kt               Cheap header-only read for duration/size
  AudioStats.kt                Full-file scan for the stats dialog (peak/RMS/clipping)
  RecordingNameFormatter.kt    Turns a recording filename into a human-readable title
  RecordingSplitDuration.kt    The supported split lengths (30/45/60 min) and their validation
  RecordingSettings.kt         Persists the user's chosen split length
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

### Tests

`./gradlew check` runs the local (JVM, Robolectric) unit tests for both debug and
release, plus lint. Tests live in `app/src/test` and run for both variants,
except the fragment UI tests, which live in `app/src/testDebug`: they use
FragmentScenario, whose host activity is a test-only manifest entry merged into
debug builds only (`fragment-testing-manifest`), never into a release build.

## Why a hand-rolled WAV writer

`AudioRecord` gives raw PCM samples with no container format. There's no WAV
muxer in the Android SDK, so `WavRecorder` writes the RIFF/WAVE header itself
(44 to 80 bytes depending on the format), patches its size fields in as
recording progresses (so a mid-recording crash or kill still leaves a playable
file), and finalizes it on stop or on each segment rollover.
