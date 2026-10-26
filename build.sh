#!/usr/bin/env bash
# Offline APK build for the RayNeo pose-probe (no Gradle, no network).
# Uses the Android SDK build-tools + Unity's bundled OpenJDK, driven from WSL.
set -euo pipefail

PROJ="/mnt/d/Work/RayNeo/rayneo-native-cube"
SDK="/mnt/c/Users/Zigzee/Android/Sdk"
BT="$SDK/build-tools/35.0.0"
JDK="/mnt/c/Program Files/Unity/Hub/Editor/6000.3.10f1/Editor/Data/PlaybackEngines/AndroidPlayer/OpenJDK/bin"

JAVAC="$JDK/javac.exe"
JAVA="$JDK/java.exe"
JAR="$JDK/jar.exe"
KEYTOOL="$JDK/keytool.exe"
AAPT2="$BT/aapt2.exe"
ZIPALIGN="$BT/zipalign.exe"
D8JAR="$BT/lib/d8.jar"
APKSIGNER_JAR="$BT/lib/apksigner.jar"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"

SRC="$PROJ/app/src"
MANIFEST="$PROJ/app/AndroidManifest.xml"
CLIENT_JAR="$PROJ/app/libs/client.jar"
JNI="$PROJ/app/jni"
OUT="$PROJ/out"

W() { wslpath -w "$1"; }   # /mnt/... -> Windows path for the .exe/.jar tools

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex"

echo "== [0/7] clang -> libslamshim.so =="
NDK="$SDK/ndk/27.2.12479018"
CLANG="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"   # .cmd wrappers are batch, unusable from bash
"$CLANG" --target=aarch64-linux-android30 -shared -fPIC -O2 -Wall \
  -o "$(W "$JNI/arm64-v8a/libslamshim.so")" "$(W "$JNI/src/slamshim.c")" -ldl -llog

echo "== [1/7] javac =="
mapfile -t JAVAS < <(find "$SRC" -name '*.java')
JW=(); for f in "${JAVAS[@]}"; do JW+=("$(W "$f")"); done
"$JAVAC" -source 8 -target 8 -nowarn \
  -classpath "$(W "$ANDROID_JAR");$(W "$CLIENT_JAR")" \
  -d "$(W "$OUT/classes")" "${JW[@]}"

echo "== [2/7] jar app classes =="
"$JAR" cf "$(W "$OUT/app.jar")" -C "$(W "$OUT/classes")" .

echo "== [3/7] d8 -> classes.dex =="
"$JAVA" -cp "$(W "$D8JAR")" com.android.tools.r8.D8 \
  --min-api 30 --lib "$(W "$ANDROID_JAR")" \
  --output "$(W "$OUT/dex")" \
  "$(W "$OUT/app.jar")" "$(W "$CLIENT_JAR")"

echo "== [4/7] aapt2 link (manifest + assets) =="
"$AAPT2" link \
  -I "$(W "$ANDROID_JAR")" \
  --manifest "$(W "$MANIFEST")" \
  -A "$(W "$PROJ/app/assets")" \
  --min-sdk-version 30 --target-sdk-version 31 \
  -o "$(W "$OUT/base.apk")"

echo "== [5/7] inject classes.dex + native libs =="
cp "$OUT/base.apk" "$OUT/unsigned.apk"
python3 - "$OUT" "$JNI" <<'PY'
import sys, os, zipfile, shutil
out, jni = sys.argv[1], sys.argv[2]
apk = os.path.join(out, "unsigned.apk")
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(out, "dex", "classes.dex"), "classes.dex")
    for abi in os.listdir(jni):
        d = os.path.join(jni, abi)
        for so in os.listdir(d):
            z.write(os.path.join(d, so), f"lib/{abi}/{so}")
print("  injected:", [n for n in zipfile.ZipFile(apk).namelist() if n.endswith(('.dex','.so'))])
PY

echo "== [6/7] zipalign =="
"$ZIPALIGN" -p -f 4 "$(W "$OUT/unsigned.apk")" "$(W "$OUT/aligned.apk")"

echo "== [7/7] sign =="
KS="$OUT/debug.keystore"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -keystore "$(W "$KS")" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi
"$JAVA" -jar "$(W "$APKSIGNER_JAR")" sign \
  --ks "$(W "$KS")" --ks-pass pass:android --key-pass pass:android \
  --out "$(W "$OUT/poseprobe.apk")" "$(W "$OUT/aligned.apk")"

echo
echo "BUILD OK -> $OUT/poseprobe.apk"
ls -la "$OUT/poseprobe.apk"
