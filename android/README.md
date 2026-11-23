# yama-remo for Android tablets (kiosk)

A native Android version of `musiccast-remote.html`, made for an old **Lenovo IdeaPad A3000-H**
(Android 4.2, 7", 1024×600) used as a wall or table remote. It runs on Android 4.0.3 and later.

The tablet's built-in browser and WebView are too old for the web page, so this is a small
Java app. It talks to the speaker's YXC HTTP API directly, so the CORS limitation doesn't apply
and `proxy.py` isn't needed.

## Features

- Power, sources (AirPlay, AUX, Optical, Bluetooth, USB, Net Radio), volume −/+ (hold to repeat),
  tap or drag on the volume bar, mute, previous, play/pause, next.
- Now playing, refreshed every 3 s. Tap the panel to refresh at once.
- When the speaker is off (or unreachable), the screen shows a big clock with the date below it.
  Only the power button and the sources stay; tapping a source switches the speaker on.
- Source buttons are along the bottom. Previous/play/next are hidden for inputs that have no
  tracks (AUX, Optical, USB), as in the web remote.
- The tablet's hardware volume keys control the speaker's volume.
- Landscape only, full screen, screen kept on.

## Kiosk mode

- The app can be set as the tablet's **Home app**. Then the Home button and every reboot bring you
  back to the remote, and Back does nothing.
- It also starts after a reboot even if it isn't the Home app; you can turn this off.
- **Admin settings:** long-press the "MusicCast Remote" title. There you can set the IP, keep
  the screen on, start after reboot, an optional PIN for this screen, and **Leave kiosk**.
- **Leave kiosk** opens the app's system page. Tap **Clear defaults** there and the normal
  launcher is the Home app again.
- On Android 4.2 the navigation bar can only be dimmed, not hidden; Android 4.4+ hides it fully.

## Updating from the tablet

The app checks for a newer version every 6 hours, and you can check by hand in the admin settings
(long-press the title, then **Check for updates now**). When a newer build is in the repo, an amber
**Update x.y** button appears in the top bar. Tap it, then tap **Install** in Android's installer.
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
