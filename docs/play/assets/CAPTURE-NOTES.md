# Store assets: preparation status

Prepared 2026-10-11. Drafts for owner review only; nothing submitted to Play.

## Feature graphic

feature-graphic-1024x500.png: opaque RGB PNG, 1024 x 500.
Created using the built-in image generation tool. See feature-graphic-prompt.md
for the exact prompt and export process. This is an illustration, not app UI.

## Screenshot provenance

Five real captures of the unmodified application, using an isolated Android 36
x86_64 emulator on Anxel, at 1080 x 1920 / density 360. No A55 content was used.
System UI demo mode provides a clean status bar and a fixed 09:00 display time;
recording dates are the actual capture-session dates, not fabricated metadata.

Build: play-store-preparation working tree, based on a199d35, with uncommitted
preparation changes. assembleRelease completed successfully. The minified release
APK was signed with the existing DEBUG certificate solely for installation in the
emulator. It is NOT a production-signed artifact or a Play-installed build.
Installed capture APK SHA256:
2977ef13bbc8111fabac75cde1ca7b9aaa270627a3bce45e3c6c247902783788

- 01-record-ready-v2.png: idle recording screen, split interval and destination.
- 02-mic-test-v2.png: live Mic Test; emulator has no real audio input.
- 03-recording-v2.png: recording with timer and Stop & save.
- 04-library-v2.png: two silent recordings created only for this capture session.
- 05-bulk-select-v2.png: both sample recordings selected; no deletion performed.

All five packaged files are RGB PNG, 1080 x 1920. Alpha was removed with a
pixel-by-pixel check that visible RGB values stayed unchanged. No crop, UI
replacement, waveform synthesis, or device-name editing was performed.

## Limitations and next steps

The Mic Test and active-recording shots show the emulator device name and a flat
signal. Replace those two with clean A55 + Insta360 captures before final store
approval. Other shots are review candidates, not automatically approved assets.
The UI header still reads BUKIN'S / wav recorder, while the application label is
Bukin Mic Recorder. This is the actual current design, not altered in the images.
Recapture if the final release UI changes.

The capture session does not demonstrate real microphone quality, recovery, or
Google Play signing. No full automated test suite was rerun for asset preparation.
The foreground-service demonstration video remains outstanding.

The emulator was shut down after capture. Its disposable data and two silent test
recordings remain on Anxel in /tmp/bukin-play-capture-kbh9p_df for reproducibility.
The user's phone recordings were neither read nor changed during capture.

No commit, push, Play submission, or production key creation was performed.

## Real-device Mic Test capture
a55-mic-test-live.png was captured from the owner-unlocked A55 while the owner
ran Mic Test with Insta360 Mic Air RX. Visible UI: verified active USB input,
signal detected, 48 kHz / 16-bit / stereo. The test was not stopped or changed.
No recording audio was copied. This screenshot supplements the emulator drafts.
It is a full-resolution 1080 x 2340 source, NOT upload-ready: its aspect ratio
exceeds the store screenshot limit. Review status-bar icons and adapt the layout
without distorting UI or removing essential controls before final submission.
preview.html now displays this real-device shot for Mic Test.
