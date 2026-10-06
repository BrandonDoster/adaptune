#!/bin/sh
# Builds build/adaptune-<version>.apk with the plain SDK tools (no Gradle).
# Needs JDK 17 and the Android SDK with platforms;android-37.0 and build-tools;37.0.0.
#
# Local builds are signed with app/debug.keystore (created on first run).
# Release builds (CI) pass the signing key and version through the environment:
#   KEYSTORE, KEYSTORE_PASSWORD, KEY_ALIAS   release signing key (PKCS12)
#   VERSION_NAME, VERSION_CODE               defaults 0.0.0-dev / 1
set -e
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
TOOLS=$SDK/build-tools/37.0.0
JAR=$SDK/platforms/android-37.0/android.jar
if [ -z "$JAVA_HOME" ]; then JAVA_HOME=$(/usr/libexec/java_home -v 17); fi
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
VERSION_NAME=${VERSION_NAME:-0.0.0-dev}
VERSION_CODE=${VERSION_CODE:-1}
OUT=build/adaptune-$VERSION_NAME.apk

mkdir -p build/classes build/dex

"$TOOLS/aapt2" compile --dir res -o build/res.zip
"$TOOLS/aapt2" link -o build/unsigned.apk --manifest AndroidManifest.xml -I "$JAR" -R build/res.zip \
    --min-sdk-version 33 --target-sdk-version 37 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME"

javac -source 17 -target 17 -Xlint:-options -cp "$JAR" -d build/classes $(find src -name '*.java')
"$TOOLS/d8" --lib "$JAR" --min-api 33 --output build/dex $(find build/classes -name '*.class')
(cd build/dex && zip -q ../unsigned.apk classes.dex)

"$TOOLS/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk

if [ -n "$KEYSTORE" ]; then
    # Passwords go through the environment, never the command line.
    "$TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass env:KEYSTORE_PASSWORD --ks-key-alias "${KEY_ALIAS:-adaptune}" \
        --out "$OUT" build/aligned.apk
else
    if [ ! -f debug.keystore ]; then
        keytool -genkeypair -keystore debug.keystore -storepass android -keypass android -alias debug \
            -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Adaptune debug"
    fi
    "$TOOLS/apksigner" sign --ks debug.keystore --ks-pass pass:android --out "$OUT" build/aligned.apk
fi
"$TOOLS/apksigner" verify "$OUT"

echo "Built $(pwd)/$OUT"
