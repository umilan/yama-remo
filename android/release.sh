#!/bin/sh
# Publishes a new Android version in two commits on main:
#   1. the APK, sources and changelog (version.json still announces the previous version)
#   2. version.json pointing at the APK by that commit's id on jsDelivr
# A jsDelivr URL with a commit id never goes stale, unlike @main, which jsDelivr caches for hours.
# Usage: android/release.sh "commit message"   (after bumping the version and adding a changelog entry)
set -e
cd "$(dirname "$0")/.."
MSG=${1:?commit message}
TRAILER="Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01RXigPz8r7sVFLM8zd2ph79"

android/build-apk.sh >/dev/null
VN=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' android/AndroidManifest.xml)
VC=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' android/AndroidManifest.xml)

git add -A android
git reset -q android/version.json            # announce only after the APK commit exists
git commit -q -m "$MSG

$TRAILER"
SHA=$(git rev-parse HEAD)
printf '{"versionCode": %s, "versionName": "%s", "apk": "https://cdn.jsdelivr.net/gh/umilan/yama-remo@%s/android/releases/yamaremo-kiosk-%s.apk"}\n' \
    "$VC" "$VN" "$SHA" "$VN" > android/version.json
git add android/version.json
git commit -q -m "Announce Android $VN

$TRAILER"
git push -q origin main
cat android/version.json
