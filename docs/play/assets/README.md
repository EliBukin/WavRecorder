# Google Play icon

play-icon-512.png is a deterministic export of the current Android launcher
vector (app/src/main/res/drawable/ic_launcher.xml), without a redesign.

- 512 x 512 pixels; 32-bit RGBA PNG; 12,466 bytes.
- Original blue background and white microphone preserved.
- No baked-in rounded corners or drop shadow.
- Prepared for owner review; not uploaded to Play.

play-icon.svg is the corresponding vector export, not the upload file.
export-icon.cjs reproduces the PNG using Node.js and sharp. Set NODE_PATH to
the directory containing sharp before running it. ic_launcher.xml is the
source snapshot; compare with the app source before regenerating.
