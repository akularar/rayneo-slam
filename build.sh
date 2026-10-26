#!/usr/bin/env bash
# Offline build (no Gradle, no network) of:
#   out/rayneo-slam-$VERSION.aar   the library (lib/)
#   out/poseprobe.apk              the sample app (app/), linked against it
# Uses the Android SDK build-tools/NDK + Unity's bundled OpenJDK, driven from WSL.
set -euo pipefail

VERSION="0.1.0"

PROJ="/mnt/d/Work/RayNeo/rayneo-native-cube"
SDK="/mnt/c/Users/Zigzee/Android/Sdk"
BT="$SDK/build-tools/35.0.0"
NDK="$SDK/ndk/27.2.12479018"
JDK="/mnt/c/Program Files/Unity/Hub/Editor/6000.3.10f1/Editor/Data/PlaybackEngines/AndroidPlayer/OpenJDK/bin"

CLANG="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"   # .cmd wrappers are batch, unusable from bash
JAVAC="$JDK/javac.exe"
JAVA="$JDK/java.exe"
JAR="$JDK/jar.exe"
KEYTOOL="$JDK/keytool.exe"
AAPT2="$BT/aapt2.exe"
ZIPALIGN="$BT/zipalign.exe"
D8JAR="$BT/lib/d8.jar"
APKSIGNER_JAR="$BT/lib/apksigner.jar"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"

LIB="$PROJ/lib"
APP="$PROJ/app"
CLIENT_JAR="$APP/libs/client.jar"     # RayNeo SDK: compile-time only for the library
OUT="$PROJ/out"
AAR="$OUT/rayneo-slam-$VERSION.aar"

W() { wslpath -w "$1"; }   # /mnt/... -> Windows path for the .exe/.jar tools

# javac every .java under $1 into $2, classpath $3
compile() {
  local JW=(); mapfile -t JAVAS < <(find "$1" -name '*.java')
  for f in "${JAVAS[@]}"; do JW+=("$(W "$f")"); done
  "$JAVAC" -source 8 -target 8 -nowarn -classpath "$3" -d "$(W "$2")" "${JW[@]}"
}

for f in "$CLIENT_JAR" "$APP"/jni/arm64-v8a/libRayNeoXR{SDKClient,LocalLoader,ApiLayerClient}.so; do
  [ -f "$f" ] || { echo "missing RayNeo client file: $f (not in git -- see README)" >&2; exit 1; }
done

rm -rf "$OUT"; mkdir -p "$OUT/lib-classes" "$OUT/app-classes" "$OUT/dex" "$OUT/jni/arm64-v8a"

echo "== [lib 1/3] clang -> libslamshim.so =="
"$CLANG" --target=aarch64-linux-android30 -shared -fPIC -O2 -Wall \
  -o "$(W "$OUT/jni/arm64-v8a/libslamshim.so")" "$(W "$LIB/jni/slamshim.c")" -ldl -llog

echo "== [lib 2/3] javac -> classes.jar =="
compile "$LIB/src" "$OUT/lib-classes" "$(W "$ANDROID_JAR");$(W "$CLIENT_JAR")"
"$JAR" cf "$(W "$OUT/classes.jar")" -C "$(W "$OUT/lib-classes")" .

echo "== [lib 3/3] package $(basename "$AAR") =="
python3 - "$OUT" "$LIB" "$AAR" <<'PY'
import sys, os, zipfile
out, lib, aar = sys.argv[1:]
with zipfile.ZipFile(aar, "w", zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(lib, "AndroidManifest.xml"), "AndroidManifest.xml")
    z.write(os.path.join(out, "classes.jar"), "classes.jar")
    z.write(os.path.join(out, "jni", "arm64-v8a", "libslamshim.so"), "jni/arm64-v8a/libslamshim.so")
    z.write(os.path.join(lib, "consumer-rules.pro"), "proguard.txt")
    z.writestr("R.txt", "")
print("  aar:", zipfile.ZipFile(aar).namelist())
PY

echo "== [app 1/6] javac =="
compile "$APP/src" "$OUT/app-classes" "$(W "$ANDROID_JAR");$(W "$CLIENT_JAR");$(W "$OUT/classes.jar")"
"$JAR" cf "$(W "$OUT/app.jar")" -C "$(W "$OUT/app-classes")" .

echo "== [app 2/6] d8 -> classes.dex =="
"$JAVA" -cp "$(W "$D8JAR")" com.android.tools.r8.D8 \
  --min-api 30 --lib "$(W "$ANDROID_JAR")" \
  --output "$(W "$OUT/dex")" \
  "$(W "$OUT/app.jar")" "$(W "$OUT/classes.jar")" "$(W "$CLIENT_JAR")"

echo "== [app 3/6] aapt2 link (manifest + assets) =="
"$AAPT2" link \
  -I "$(W "$ANDROID_JAR")" \
  --manifest "$(W "$APP/AndroidManifest.xml")" \
  -A "$(W "$APP/assets")" \
  --min-sdk-version 30 --target-sdk-version 31 \
  -o "$(W "$OUT/base.apk")"

echo "== [app 4/6] inject classes.dex + native libs (RayNeo + libslamshim) =="
cp "$OUT/base.apk" "$OUT/unsigned.apk"
python3 - "$OUT" "$APP/jni" "$OUT/jni" <<'PY'
import sys, os, zipfile
out, *jnis = sys.argv[1:]
apk = os.path.join(out, "unsigned.apk")
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(out, "dex", "classes.dex"), "classes.dex")
    for jni in jnis:
        for abi in os.listdir(jni):
            d = os.path.join(jni, abi)
            for so in os.listdir(d):
                if so.endswith(".so"):
                    z.write(os.path.join(d, so), f"lib/{abi}/{so}")
print("  injected:", [n for n in zipfile.ZipFile(apk).namelist() if n.endswith(('.dex','.so'))])
PY

echo "== [app 5/6] zipalign =="
"$ZIPALIGN" -p -f 4 "$(W "$OUT/unsigned.apk")" "$(W "$OUT/aligned.apk")"

echo "== [app 6/6] sign =="
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
echo "BUILD OK"
ls -la "$AAR" "$OUT/poseprobe.apk"
