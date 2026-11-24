# Working rules for this repo

## Branch

Work and push directly on `main` (the owner asked for this). The tablet's self-update reads
`android/version.json` from `main` via jsDelivr, so a version only reaches the tablet once it is on `main`.

## Versioning (Android kiosk app, `android/`)

Every feature or fix to the Android app ships as a new version:

1. Bump the version in `android/AndroidManifest.xml`:
   - `android:versionCode` + 1, always. Self-update only offers builds with a higher versionCode.
   - `android:versionName`: new feature → minor (1.3 → 1.4); fix only → patch (1.4 → 1.4.1).
2. Add an entry at the top of `android/CHANGELOG.md`.
3. Run `android/release.sh "<commit message>"`. It builds (`android/build-apk.sh`), commits the APK
   (`android/yamaremo-kiosk.apk` and `android/releases/yamaremo-kiosk-<versionName>.apk`) and sources,
   then commits `android/version.json` pointing at the APK by that commit's id on jsDelivr
   (`cdn.jsdelivr.net/gh/umilan/yama-remo@<sha>/…`), and pushes both. jsDelivr caches `@main` for
   hours, but a commit-id URL is always fresh; the tablet reads version.json mainly from githack.

## Reporting

End every finished reply with one last line: `DONE`, then the app version as a markdown link whose
target is the direct download of that version's APK. No separate download line.

    DONE · [Android app v1.4](https://github.com/umilan/yama-remo/raw/main/android/releases/yamaremo-kiosk-1.4.apk)

When the WinCE app (`wince/`, version in
`APP_VERSION` in `yamaremo.c`) changed too, add it the same way:
`DONE · [Android app v1.4](…apk) · [WinCE app v1.1](…exe)`.
