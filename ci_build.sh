#!/usr/bin/env bash
# CI 构建脚本（无需 Gradle/AGP，直接使用 SDK 工具链）
set -e
cd "$(dirname "$0")"

BT="$ANDROID_HOME/build-tools/34.0.0"
PLAT="$ANDROID_HOME/platforms/android-34/android.jar"

rm -rf build
mkdir -p build/gen build/classes build/dex

echo "== 1/6 aapt2 compile =="
"$BT/aapt2" compile --dir res -o build/res.zip

echo "== 2/6 aapt2 link =="
"$BT/aapt2" link -o build/base.apk -I "$PLAT" \
    --manifest AndroidManifest.xml \
    --java build/gen --min-sdk-version 24 --target-sdk-version 34 \
    --auto-add-overlay build/res.zip

echo "== 3/6 javac =="
javac --release 8 -encoding UTF-8 -classpath "$PLAT" -d build/classes \
    build/gen/com/yao/jdck/R.java java/com/yao/jdck/MainActivity.java

echo "== 4/6 d8 =="
"$BT/d8" --release --lib "$PLAT" --min-api 24 --output build/dex \
    $(find build/classes -name '*.class')

echo "== 5/6 repack + zipalign =="
python3 repack.py build/base.apk build/dex/classes.dex build/unsigned.apk
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

echo "== 6/6 apksigner =="
KS="debug.keystore"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -validity 10000 \
        -dname "CN=Android Debug,O=Android,C=US"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey --out JDCK.apk build/aligned.apk

echo "BUILD OK: JDCK.apk"
