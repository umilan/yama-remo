# Working rules for this repo

## Versioning (Android kiosk app, `android/`)

Every feature or fix to the Android app ships as a new version:

1. Bump the version in `android/AndroidManifest.xml`:
   - `android:versionCode` + 1, always. Self-update only offers builds with a higher versionCode.
   - `android:versionName`: new feature → minor (1.3 → 1.4); fix only → patch (1.4 → 1.4.1).
2. Add an entry at the top of `android/CHANGELOG.md`.
3. Run `android/build-apk.sh`. It writes `android/yamaremo-kiosk.apk` (latest),
   `android/releases/yamaremo-kiosk-<versionName>.apk` (kept), and `android/version.json`.
4. Commit all of them and push.

## Reporting

End every finished reply with the app version and a direct download link, then `DONE`:

    Android app **v1.4** — download: https://github.com/umilan/yama-remo/raw/<branch>/android/releases/yamaremo-kiosk-1.4.apk

Use the branch the commit was pushed to (`main` once merged). Name the WinCE app version too when it
changed (`wince/`, version in `APP_VERSION` in `yamaremo.c`).
