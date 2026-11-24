# Android kiosk app — changelog

## 1.3 (versionCode 4)
- New minimal black design: text source tabs with OFF at the top, big centred track and artist,
  outline transport icons with captions, dotted volume bar (one dot per step).
- Shuffle and Repeat controls (Repeat cycles off → all → one).
- Speaker off: big clock with uppercase date; Settings, update and speaker IP along the bottom.
- Double-tap the clock (or tap Settings) to open settings.
- USB tab and Mute button removed; muted state shows as amber dots and artist line.

## 1.2 (versionCode 3)
- Self-update: checks `version.json` every 6 hours or on demand, downloads the APK and opens the installer.
- TLS 1.2 enabled on Android 4.1–4.4 for GitHub downloads.

## 1.1 (versionCode 2)
- Big clock and date when the speaker is off or unreachable.
- Sources moved to the bottom; Prev/Play/Next hidden for AUX, Optical and USB.

## 1.0 (versionCode 1)
- First release: power, sources, volume, mute, transport, now playing, kiosk mode (Home app),
  start on boot, PIN-protected admin settings.
