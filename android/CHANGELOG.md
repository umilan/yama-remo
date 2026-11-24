# Android kiosk app — changelog

## 1.7 (versionCode 10)
- Action row is now Prev · Shuffle · Settings · Pause · Mute · Repeat · Next.
  Settings opens the settings (PIN if set); Mute toggles mute and turns amber ("Unmute") while muted.
- AUX / Optical / USB: the action row stays, showing only Settings and Mute in their usual places.

## 1.6 (versionCode 9)
- Playback controls row (Prev / Shuffle / Pause / Repeat / Next) fades out after 10 s without a
  touch and keeps its space, so nothing jumps. Any touch brings it back; a tap on the hidden row
  only reveals it and doesn't press a button.
- ADVANCED setting "Hide playback controls after (seconds, 0 = never)".

## 1.5 (versionCode 8)
- Volume bar sits closer to the bottom edge: the bottom row is 48 dp instead of 88 dp.
- New ADVANCED section in settings with "Volume bar without bottom padding" (on by default);
  turn it off to get the previous spacing back.

## 1.4 (versionCode 7)
- Song and artist always on separate rows. For Radio, the combined "Artist - Song" text from the
  station is split (separators ` - `, ` – `, ` — `, ` ~ `, ` | `), and the station name is shown
  small underneath. Without a separator, the whole text is the song.

## 1.3.2 (versionCode 6)
- "Net Radio" tab renamed to "Radio".

## 1.3.1 (versionCode 5)
- Fix: update check failed on Android 4.2 with "SSL handshake aborted … unsupported protocol".
  The app now opens HTTPS connections itself with TLS 1.2/1.1/1.0 and SNI, and doesn't fall
  back to SSLv3.
- Update sources tried in order: jsDelivr, GitHub raw, plain-HTTP jsDelivr. An empty update URL
  (or the old GitHub default) means automatic. When every source fails, the error lists each one.

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
