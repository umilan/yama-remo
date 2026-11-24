# yama-remo for Android tablets (kiosk)

A native Android version of `musiccast-remote.html`, made for an old **Lenovo IdeaPad A3000-H**
(Android 4.2, 7", 1024×600) used as a wall or table remote. It runs on Android 4.0.3 and later.

The tablet's built-in browser and WebView are too old for the web page, so this is a small
Java app. It talks to the speaker's YXC HTTP API directly, so the CORS limitation doesn't apply
and `proxy.py` isn't needed.

## Features

- Black, minimal screen. Sources are text tabs along the top (AirPlay, AUX, Optical, Bluetooth,
  Radio), with **OFF** at the end to put the speaker in standby.
- Playing: big song title with the artist on its own row below it, both white (for Radio, the station's
  "Artist - Song" text is split into the two rows and the station name is shown small underneath), then Prev / Shuffle / Settings / Play-Pause / Mute / Repeat / Next
  as outline icons with labels. Shuffle and Repeat are dimmed when off; tapping Repeat cycles
  off → all → one.
- Volume: − and + at the ends of a dotted bar with one dot per volume step. Tap or drag on the dots
  to jump, and hold −/+ to repeat. The tablet's hardware volume keys also control the speaker.
- Swipe on the song area: drag up/down for volume, swipe left/right for next/previous.
- Radio: Prev/Next switch to the previous/next station saved as a favourite (preset) in the
  MusicCast app.
- Inputs without tracks (AUX, Optical, USB) show only Settings and Mute in that row. The row fades out after
  10 s without a touch (configurable) and comes back on any touch.
- Speaker off or unreachable: a big clock with the date in capitals (`MONDAY, 2026 / 11 / 23`).
  The bottom row shows **Settings** on the left, the speaker IP on the right (red when it can't
  be reached) and **Update x.y** in the middle when a newer version exists.
- Short messages appear in the top (song) row for a moment: volume level while you change it,
  Shuffle/Repeat state, radio notices. While muted it shows "Muted" in amber, the dots turn amber
  and the Mute button shows Unmute.

## Kiosk mode

- The app can be set as the tablet's **Home app**. Then the Home button and every reboot bring you
  back to the remote, and Back does nothing.
- It also starts after a reboot even if it isn't the Home app; you can turn this off.
- **Settings:** double-tap the clock, tap **Settings** under it, or tap Settings in the action row. There you can set the IP, keep
  the screen on, start after reboot, an optional PIN for this screen, the update URL, the ADVANCED
  options (volume bar without bottom padding, hide playback controls after N seconds), and **Leave kiosk**. Tap the IP under the clock to change only the speaker IP.
- **Leave kiosk** opens the app's system page. Tap **Clear defaults** there and the normal
  launcher is the Home app again.
- On Android 4.2 the navigation bar can only be dimmed, not hidden; Android 4.4+ hides it fully.

## Updating from the tablet

The app checks for a newer version every hour (and clears jsDelivr's cache first, so new versions show up right away), and you can check by hand in the admin settings
(double-tap the clock, then **Check for updates now**). When a newer build is in the repo, an amber
**Update x.y** appears under the clock. Tap it, then tap **Install** in Android's installer.
Your settings are kept.

- It reads `android/version.json` from the `main` branch on GitHub:
  `https://raw.githubusercontent.com/umilan/yama-remo/main/android/version.json`.
  `build-apk.sh` writes that file, so publishing an update means building, committing, and merging to `main`.
- You can change the URL in the admin settings, for example to test a branch or to use a copy on your
  NAS over plain `http://`. Put `version.json` and the APK in the same folder.
- On Android 4.x the app turns on TLS 1.2 itself. If the check still fails with an SSL error, the
  tablet's certificate store is too old for GitHub. In that case use a NAS or other `http://` URL.
- Android 4.x can't update apps silently, so tapping **Install** is always needed.
- The first install of 1.2 has to be done by hand (adb or file manager). Later versions can update from the tablet.

## Install

`yamaremo-kiosk.apk` is in this folder (prebuilt).

1. On the tablet: Settings → Security → turn on **Unknown sources**.
2. Install the APK in either of these ways:
   - **USB cable with adb.** First turn on Settings → About tablet → tap *Build number* 7 times,
     then Settings → Developer options → **USB debugging**. Then run:
     `adb install -r yamaremo-kiosk.apk`
   - **Without a computer.** Copy the APK to the tablet (USB storage, SD card, or download it in
     the tablet's browser) and open it with a file manager.
3. Start **MusicCast Remote** and enter the speaker's IP.
4. Press the Home button, choose **MusicCast Remote**, and pick **Always**. That turns on kiosk mode.

## Build

```sh
./build-apk.sh
```

The build uses the distro packages listed at the top of the script; Gradle and Android Studio
aren't needed. `debug.keystore` is a throwaway signing key, committed on purpose so that new
builds install as updates over the old one.
