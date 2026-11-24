# Android kiosk app — changelog

## 1.24 (versionCode 31)
- "Check for updates now": when a newer version exists, the dialog has an "Update now" button that
  downloads it and opens the installer right away ("Later" closes it).

## 1.23 (versionCode 30)
- Song title row in full white (was dimmed); the artist stays white.

## 1.22.1 (versionCode 29)
- Fix: no tab was highlighted when the speaker plays from a source without a tab (Spotify, Server,
  USB, TIDAL…). A white tab with that source's name now appears before OFF while it's active.
- Readable names for the speaker's other inputs.

## 1.22 (versionCode 28)
- Updates: the app bundles 23 current root CA certificates (Let's Encrypt ISRG, Google GTS, DigiCert
  G2/G3, Sectigo/USERTrust, Amazon, GlobalSign…) and trusts them in addition to Android 4.2's own
  list, for update checks only. This fixes "Trust anchor for certification path not found" when
  clearing jsDelivr's cache, so jsDelivr serves the current version.
- Speaker IP is checked on Save: an incomplete address like "192.168.13" is rejected with a message.
- ADVANCED connection line shows the last network error (timeout, host unreachable, HTTP code).

## 1.21.1 (versionCode 27)
- First setup: the speaker IP field is prefilled with 192.168.1.13; tap Save to use it.

## 1.21 (versionCode 26)
- Volume row placed so the space above it (below the action-row captions) equals the space below it
  down to the physical screen edge, counting the navigation bar Android 4.x can only dim. On the
  A3000 the dots sit about 12 dp from the bottom of the app area instead of 24 dp. Touch areas are
  unchanged.

## 1.20 (versionCode 25)
- Update check asks every source and takes the highest version, so one stale cache can't hide
  an update. New first source: raw.githack.com (GitHub mirror that caches only minutes).
- "Check for updates now" shows a dialog with the installed version and what each source answered
  (version or error), plus whether clearing jsDelivr's cache worked.
- Shorter network timeouts for update sources, so a dead source doesn't stall the check.

## 1.19.1 (versionCode 24)
- "Check for updates now" reports what it saw: installed version, the version on the server, which
  source answered, and whether clearing jsDelivr's cache worked.

## 1.19 (versionCode 23)
- Inactive tabs in the top row (sources and OFF) dimmed to 25 % of the active white.

## 1.18 (versionCode 22)
- The top (song) row carries short messages; the artist row always shows the artist:
  - "Muted" (amber) while muted; "Unmuted" briefly when unmuting.
  - "Volume 34" for 1.5 s whenever the volume changes (−/+, dots, hardware keys), with "· max" at
    the top and "· muted" while muted.
  - "Shuffle on/off", "Repeat all/one/off".
  - Radio notices ("No saved stations…", "Couldn't switch to …") instead of pop-up toasts.

## 1.17 (versionCode 21)
- Tapping the now-playing area toggles the action row: hides it right away when visible, shows it
  when hidden. Works even with auto-hide switched off. Touches elsewhere still only show it.

## 1.16 (versionCode 20)
- Radio: Prev/Next ("Prev station" / "Next station") step through the radio stations saved as
  favourites (presets) in the MusicCast app, wrapping around at the ends. Stepping starts from the
  station that is playing when it's one of the presets.
- "Tuning in… <station>" while the new station starts, instead of the previous station's song.

## 1.15 (versionCode 19)
- Faster updates:
  - Before every check the app asks jsDelivr to drop its cached `version.json` (purge), so a new
    version shows up right away instead of up to 12 hours later.
  - `version.json` now points at the per-version APK (`releases/yamaremo-kiosk-x.y.apk`), so a stale
    cached APK can't be downloaded.
  - Automatic check every hour, also while the app stays in front (before, it only ran when the
    app came back to the foreground, which almost never happens in kiosk mode).

## 1.14 (versionCode 18)
- Colours swapped while a song plays: artist in full white, song title dimmed grey.

## 1.13 (versionCode 17)
- ADVANCED checkbox "Hide playback controls when not touched" (on by default) to switch auto-hide
  on or off; the seconds field below it is greyed out while it's off.

## 1.12.1 (versionCode 16)
- Fix: opening settings no longer focuses the IP field and pops up the keyboard; it opens only when
  you tap a field.

## 1.12 (versionCode 15)
- Radio playing without song info (the station sends "-") shows "No track info" with the station
  name below, instead of a lone "-" and "Radio". Any title without letters or digits counts as no info.
- Radio song without an artist: the station name takes the artist row instead of "Radio".

## 1.11 (versionCode 14)
- When the action row fades out, the now-playing text slides down to the middle of the freed space;
  it slides back up when the row returns.

## 1.10 (versionCode 13)
- Radio station names without the trailing location: "Rádio_FM (Bratislava/Slovak)" → "Rádio_FM".
  ADVANCED checkbox "Hide location in radio station names" (on by default).
- Radio titles like "Gabriels-Blood On My Hands" (dash without spaces) are split into artist and
  song when the text has exactly one dash.

## 1.9 (versionCode 12)
- Far fewer false "not connected" screens:
  - The screen switches to "not connected" only after 3 failed checks in a row (about 10 s);
    configurable in ADVANCED. A failed command no longer flips the screen on its own.
  - Failed status checks are retried once right away (read-only requests only).
  - Timeouts raised to 4 s connect / 6 s read.
  - HTTP keep-alive off (Android 4.x reuses dead pooled connections).
  - Wi-Fi held in high-performance mode while the app is in front (no Wi-Fi power saving).
- ADVANCED shows connection health: failed requests, the longest run of failed checks, last failure.
- Note: the app never sends standby on its own; only the OFF tab does.

## 1.8 (versionCode 11)
- Contextual messages instead of "Not playing":
  - "Turning on…" for up to 8 s after switching the speaker on.
  - "Switching to Radio…" (etc.) for up to 8 s after picking another source.
  - Radio: "Tuning in…" with the station while a stream starts; "<station> – Stopped, tap Play to
    resume"; "Choose a station in the MusicCast app" when no station is known.
  - AirPlay: "Waiting for AirPlay – choose this speaker on your iPhone, iPad or Mac".
  - Bluetooth: "Waiting for Bluetooth – connect your phone to the speaker".
  - "Paused" when paused without song info.
- After switching on/off or changing source, a status poll that still reports the old state no
  longer flips the screen back for 8 s.

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
