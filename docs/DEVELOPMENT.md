# Developer guide

Build instructions and implementation details for WavRecorder.
For a short overview of the app, see the [README](../README.md).

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
recording, and no file is created until the microphone has been accepted. A
start stopped while its opened microphone is on its way to the screen is
released by the cleanup the stop queues on that same thread -- ahead of any
start made after it -- even if the screen receives the handoff first; the
screen then leaves it alone rather than release it later, behind the new
start.

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

**Capture and storage are decoupled.** During a recording, one thread reads the
microphone and another -- the only one that touches files -- writes. The capture
thread hands whole frames to the writer through a bounded queue of reusable
buffers holding up to 10 seconds of audio (capped at 48 MiB for very high
formats; buffers are allocated only as the queue fills). A slow write, a slow
provider creating the next file at a split, a header flush or a forced write
therefore never stops the microphone being drained. If storage falls more than
the queue behind, recording stops with an explicit error ("Storage couldn't keep
up") rather than dropping audio or growing memory without limit; everything
accepted up to then is written and saved. The capture thread never waits for
room in the queue -- a wait would itself stop the microphone being drained -- and
it doesn't guess from timing whether the microphone is "ahead of real time":
the queue's 10 seconds are the whole policy. On stop, every accepted buffer is
written before the file is finalized; if the microphone's read never returns,
the writer waits at most half the stop timeout for it, then finalizes without
it.

A write that fails -- while recording, or while draining the queue at the stop
-- ends the session as *incomplete*, never as saved: the file is finalized as a
valid WAV of the audio written up to the failure, and the user is told once
that it was saved only up to that point. Only a session in which every accepted
buffer reached storage is reported as saved.

What a session reports as saved is what actually holds audio. A file that
receives no audio is deleted when it is closed, so no result ever points at
one: a partial save names the last file that holds audio (an earlier part, if
the failure came before any audio reached the file after it), and a session
that saved no audio at all -- a write failing before the first frame reached
storage, or a microphone that failed before delivering any -- says nothing was
saved, never "saved up to that point". Silent audio that was written counts as
saved audio, and so do the whole frames a failing write managed to store. A
failure finalizing a file that holds audio (its header or close) keeps its own
meaning: the file may need recovery, and its crash-recovery record is kept.

A write that fails may have stored more than it reported. So after any failed
write -- whether or not audio was confirmed before it -- finalization first
checks the file's size: if it shows at least one whole frame past the confirmed
audio, or can't be read, the file is kept as it is (never deleted or truncated,
and no pad byte written over what may be audio), its header describes the
confirmed audio, its recovery record is kept, and the recording is reported as
possibly needing recovery; recovery later settles it from what is physically
there. Less than a frame past it is an incomplete frame, not audio.

A file with no audio is deleted -- and its recovery record cleared -- only once
it is certain there is nothing in it to keep and that it is really gone. If
deleting it can't be confirmed after its close failed, it is reported as
possibly needing recovery rather than as gone. An empty file whose close failed
but which was deleted cost no audio: it is reported only when it explains a
session that saved nothing.

**Crash-recovery records.** Each file being recorded has a durable record in
the segment journal while it is open. Only the recording's own writer settles
it, once it has finished with the file for good (finalizing it closed it,
whatever the outcome) -- never a stop request, a timeout or the screen: a
cleanly finalized file's record is cleared; one that may need recovery is moved,
in one commit, to the recovery queue, which the next service start repairs in
the background. So a failed recording neither loses its recovery record nor
blocks the next recording in the same process, and a writer still stuck in a
native call keeps its record (and new recordings wait) until it really is done.
Settling touches only that file's own record, and doing it twice changes
nothing. If the journal can't be written, the record stays exactly as it was
and settling is retried before the next recording and at the next service
start.

The capture thread checks the microphone's route and connection on its own
schedule, whatever the reads return, so a source that keeps returning nothing
is still checked. If no audio at all -- not even silence -- arrives for 10
seconds (a read that never returns, or one that keeps returning no data), the
recording ends as stalled and what was captured is saved. The microphone test
applies the same limit, watched by a thread of its own, since a read that never
returns can't check anything itself; the watchdog can't make that read return,
but it ends the test at the limit and whatever the read delivers afterwards is
ignored. Silent audio is audio: a muted microphone, or another
app's call taking priority, delivers zero-valued samples and keeps recording.
Every interval is measured on a monotonic clock, never the wall clock. The live
level is posted to the screen at most every 50 ms (the loudest peak since the
last update).

**Deleting recordings.** A file being recorded, finalized or repaired by crash
recovery can't be deleted: `RecordingOwnership` records which files are in use,
and every delete goes through `DestinationManager.deleteRecording`. Whether a
file may be claimed or deleted is decided atomically under one lock, but no
storage I/O ever runs under it: a delete marks the file as being deleted, removes
it outside the lock, then clears the mark, and a file being deleted can't be
claimed. A new file is created outside the lock, claimed, and checked to still
exist; if a delete won the race, the writer creates another file instead, so it
never writes into a deleted one. One window remains: a delete that removes the
new file before even its first existence check makes it look like a file that is
only created when first opened. An app-storage file is then simply recreated by
opening it; a document in a folder you picked can't be opened, so the recording
ends with an error before anything is written. The Library's per-row lookups read a published
snapshot without taking the lock, so a slow provider delete never stalls the
screen. A delete from the Library therefore can't slip in, whatever the screen
showed when it was confirmed. The Library labels such a file "Recording in progress" and offers
no delete, playback or selection for it; finished files, including the completed
parts of a recording that is still going, stay deletable.

The "Recording saved" summary shows the audio actually recorded, counted from
the frames written across all of a session's files and stored with the outcome
at the stop -- not the time since the recording started, whenever it is shown.
A permission request remembers what it was for (a recording or a microphone
test) across the screen being recreated, and its result is used once: a grant
never starts anything nobody asked for.

Unplugging headphones pauses playback, and it stays paused until the user
plays it again: no later audio-focus change -- a notification sound's transient
loss and gain, say -- can start it on the speaker, whether the track was playing
or still loading. Only a pause caused by a transient focus loss, of a track the
user still wants playing, is resumed when focus returns.

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

- Android 7.0 (API 24) or newer. The app targets Android 16 (API 36), the
  Google Play requirement since 31 August 2026.
- Microphone permission (requested at first recording). Android 13+ also
  requests notification permission, needed to show the recording-in-progress
  notification.

## Project structure

```
app/src/main/java/bukin/wavrecorder/
  MainActivity.kt            Hosts the Record/Library pages (ViewPager2 + bottom navigation);
                              draws edge-to-edge and applies the window insets
  AboutDialogFragment.kt      About & privacy (version, privacy policy) and open-source licenses
  RecordFragment.kt          Record tab UI; binds to RecordingService, has no
                              recording logic of its own
  RecordingService.kt        Foreground service that owns the WavRecorder instance,
                              the notification, and the recording lifecycle
  WavRecorder.kt              Core capture engine: the capture thread, the writer
                              (files, headers, rollover, finalization) and the
                              bounded queue between them
  RecordingOwnership.kt       Which files are being written, finalized or repaired,
                              so they can't be deleted
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
                              playback (focus, speed, seeking, noisy-receiver; stops
                              when the app leaves the foreground)
  AppForeground.kt            Whether the whole app is in the foreground (ProcessLifecycleOwner)
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
the Android SDK command-line tools (platform 36, build-tools 35+) and JDK 21.
The app compiles to Java 17 bytecode, but the unit tests run Robolectric on
Android 16, which needs JDK 21.

Toolchain, chosen as the newest mutually compatible set (no AGP 9 migration):
Android Gradle plugin 8.13.2 (supports up to API 36.1, needs Gradle 8.13+),
Gradle 8.14.6 (wrapper distribution checksum pinned in
`gradle/wrapper/gradle-wrapper.properties`), Kotlin 2.3.10 (supports AGP up to
9.0 and Gradle up to 9.0) and Robolectric 4.16.1 (the first release with
Android 16). Library versions are only raised when something needs it.

```bash
export JAVA_HOME="/path/to/jdk-21"
export ANDROID_HOME="/path/to/android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
./gradlew :app:assembleDebug
```

On Windows, use `gradlew.bat :app:assembleDebug` with `JAVA_HOME` and
`ANDROID_HOME` configured.

Release builds are never signed by Gradle: see [RELEASING.md](RELEASING.md) for
signing an upload to Google Play with the upload key, version codes and release
records, and [play/PUBLICATION-CHECKLIST.md](play/PUBLICATION-CHECKLIST.md) for
what publication still needs.

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

Every test that records, runs the microphone test or opens a screen declares
the `TestIsolation` rule. After the test and its `@After` methods, it closes
the screens the test registered with it, then waits (bounded) until every
recording, microphone-test, finalization, recovery and delete thread the test
started has ended, the shared audio worker is idle, and no screen of the test
is still registered for recording-ownership changes -- and fails the test,
naming what is still running, if not. Work left running by one test used to
reach into later ones and make them fail intermittently. Tests that simulate a
stuck microphone release it in `@After`, never by simply outlasting the test.

`ProcessDeathRestorationTest` covers restoring after Android kills the app's
process: pending permission requests, persisted outcomes and an interrupted
recording. It keeps what Android keeps (preferences, files, and the saved
Activity state round-tripped through a Parcel) and builds fresh Activity,
Fragment and service objects from it. It can't restart the JVM or the
Application itself, so a real process kill still needs checking on a device.

## Library playback and the app lifecycle

Library playback has no foreground service or notification, so it stops when
the app leaves the foreground. `AppForeground` (`ProcessLifecycleOwner`)
reaches ON_STOP about 700 ms after the app's last activity stops: Home,
another app, the screen turning off. On that, `LibraryFragment` releases the
player, its audio focus and the progress updates, and the list shows the track
stopped. Nothing resumes when the user returns.

Neither of these is the app leaving the foreground:

- **Switching to the Record page.** ViewPager2 only caps the Library page at
  STARTED, so playback continues.
- **A configuration change.** The activity is recreated within the delay. It
  still ends playback by destroying the Library view, as it always has.

Late player callbacks are ignored, because every MediaPlayer callback checks
that it belongs to the current player. Recording (its foreground service) and
Mic Test (stopped by the Record page itself) are unaffected.
`LibraryBackgroundPlaybackTest` covers this.

## System bars, backup and privacy

**Edge-to-edge.** `MainActivity` draws behind the system bars on every Android
version (Android 15+ enforces it, and Android 16 removed the opt-out) and
applies the insets itself, once: the header takes the status bar and top
cutout, the bottom navigation the navigation bar, and the header, pages and
bottom navigation are kept clear of side insets (a landscape navigation bar or
cutout). The insets are consumed there, so Material's bottom navigation doesn't
pad itself a second time. No scrim is drawn behind 3-button navigation, so the
bottom navigation's background shows through. `EdgeToEdgeTest` covers this.

**Backup.** `res/xml/backup_rules.xml` (Android 6–11) and
`res/xml/data_extraction_rules.xml` (Android 12+, cloud backup and device
transfer) are an allowlist: only `wav_recorder_settings.xml` (the split length)
is backed up. Recordings, the picked folder, the crash-recovery journal and the
pending outcome stay on the device. `BackupRulesTest` ties the rules to the
preference files the code really writes, so moving a setting or renaming a
file fails the tests. Note that Android clears **all** of an app's data before
applying a full restore (`bmgr restore` on an installed app deletes its
recordings too); normal restores only happen when the app is installed.

**About & privacy.** The info button in the header opens the privacy policy
(`privacy_policy_html`, the in-app copy of
[privacy-policy.md](privacy-policy.md)) and the open-source licenses
([THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)). Keep the copies in sync;
`scripts/render-privacy-policy.py` regenerates the static
`privacy-policy.html` from the Markdown.

## Why a hand-rolled WAV writer

`AudioRecord` gives raw PCM samples with no container format. There's no WAV
muxer in the Android SDK, so `WavRecorder` writes the RIFF/WAVE header itself
(44 to 80 bytes depending on the format), patches its size fields in as
recording progresses (so a mid-recording crash or kill still leaves a playable
file), and finalizes it on stop or on each segment rollover.
