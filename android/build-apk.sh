#!/bin/sh
# Builds yamaremo-kiosk.apk without Gradle/Android Studio.
# Debian/Ubuntu tools:  sudo apt-get install android-sdk-platform-23 aapt \
#     dalvik-exchange apksigner zipalign default-jdk-headless
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
DX=${DX:-/usr/lib/android-sdk/build-tools/debian/dx}
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/obj"

aapt package -f -m -J "$OUT/gen" -M AndroidManifest.xml -S res -I "$SDK"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$SDK" \
      -d "$OUT/obj" $(find src "$OUT/gen" -name '*.java')
"$DX" --dex --min-sdk-version=15 --output="$OUT/classes.dex" "$OUT/obj"
aapt package -f -M AndroidManifest.xml -S res -I "$SDK" -F "$OUT/unsigned.apk"
(cd "$OUT" && aapt add unsigned.apk classes.dex >/dev/null)
zipalign -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

# Debug key kept in the repo on purpose, so every build can update the
# installed app in place (Android rejects updates signed with another key).
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android \
    -keypass android -alias yamaremo -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=yama-remo debug" >/dev/null 2>&1
apksigner sign --ks debug.keystore --ks-pass pass:android --ks-key-alias yamaremo \
    --min-sdk-version 15 --out yamaremo-kiosk.apk "$OUT/aligned.apk"
apksigner verify --min-sdk-version 15 yamaremo-kiosk.apk
# version.json tells installed apps that this build is available (self-update)
VC=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' AndroidManifest.xml)
VN=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' AndroidManifest.xml)
# apk points at the per-version file, so a cached copy of an older APK can never be served
printf '{"versionCode": %s, "versionName": "%s", "apk": "releases/yamaremo-kiosk-%s.apk"}\n' "$VC" "$VN" "$VN" > version.json
mkdir -p releases
cp yamaremo-kiosk.apk "releases/yamaremo-kiosk-$VN.apk"
cat version.json
ls -l yamaremo-kiosk.apk
