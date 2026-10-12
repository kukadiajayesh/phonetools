#!/usr/bin/env bash
# Builds phonetools.apk with the Android SDK tools (no Gradle). Run from Git Bash.
set -e
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$LOCALAPPDATA/Android/Sdk}"
BT="$SDK/build-tools/36.1.0"
JAR="$SDK/platforms/android-35/android.jar"
JBR="/c/Program Files/Android/Android Studio/jbr/bin"

rm -rf build && mkdir -p build/gen build/classes build/dex
"$BT/aapt2.exe" compile --dir res -o build/res.zip
"$BT/aapt2.exe" link -o build/unsigned.apk -I "$JAR" --manifest AndroidManifest.xml --java build/gen build/res.zip \
    --min-sdk-version 26 --target-sdk-version 26
"$JBR/javac.exe" -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "$JAR" -d build/classes \
    $(find src build/gen -name '*.java')
"$BT/d8.bat" --min-api 26 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
cp build/unsigned.apk build/app.apk
"$JBR/jar.exe" uf build/app.apk -C build/dex classes.dex
"$BT/zipalign.exe" -f 4 build/app.apk build/aligned.apk
"$BT/apksigner.bat" sign --ks "$USERPROFILE/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android \
    --out phonetools.apk build/aligned.apk
echo "Built phonetools.apk"
