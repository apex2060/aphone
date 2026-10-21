#!/bin/sh
# Rebuild the arm64 libpjsua2.so that sipspike ships.
# CI does not run this. The libraries under src/main/jniLibs are the cached output.
set -eu

NDK="${ANDROID_NDK_ROOT:-/home/ubuntu/android-sdk/ndk/28.2.13676358}"
ROOT="$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)"
WORK="${WORK:-/tmp/sipspike-pjsip}"
mkdir -p "$WORK"
cd "$WORK"

if [ ! -d openssl-3.5.4 ]; then
  curl -fsSL -o openssl-3.5.4.tar.gz https://github.com/openssl/openssl/releases/download/openssl-3.5.4/openssl-3.5.4.tar.gz
  tar -xzf openssl-3.5.4.tar.gz
fi
if [ ! -d openssl-arm64/lib ]; then
  (
    cd openssl-3.5.4
    export ANDROID_NDK_ROOT="$NDK"
    export PATH="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
    ./Configure android-arm64 -D__ANDROID_API__=26 no-shared no-tests no-apps \
      --prefix="$WORK/openssl-arm64" --openssldir="$WORK/openssl-arm64"
    make -j"$(nproc)"
    make install_sw
  )
fi

if [ ! -d pjproject-2.17 ]; then
  curl -fsSL -o pjproject-2.17.tar.gz https://github.com/pjsip/pjproject/archive/refs/tags/2.17.tar.gz
  tar -xzf pjproject-2.17.tar.gz
fi
cd pjproject-2.17
patch -p1 < "$ROOT/third_party/patches/pjsip-2.17-spike.patch"
cp "$ROOT/third_party/config_site.h" pjlib/include/pj/config_site.h

export ANDROID_NDK_ROOT="$NDK"
export APP_PLATFORM=android-26
export TARGET_ABI=arm64-v8a
export LDFLAGS="-Wl,-z,max-page-size=16384"
./configure-android --use-ndk-cflags --with-ssl="$WORK/openssl-arm64"
make dep -j"$(nproc)"
make -j"$(nproc)"

mkdir -p pjsip-apps/src/swig/java/output
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang" \
  -fPIC -c "$ROOT/third_party/spike_jni.c" \
  -o pjsip-apps/src/swig/java/output/spike_jni.o \
  -I pjlib/include \
  -DPJ_AUTOCONF=1 -DPJ_IS_LITTLE_ENDIAN=1 -DPJ_IS_BIG_ENDIAN=0 -DANDROID
make -C pjsip-apps/src/swig/java -j"$(nproc)"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded \
  pjsip-apps/src/swig/java/android/pjsua2/src/main/jniLibs/arm64-v8a/libpjsua2.so \
  pjsip-apps/src/swig/java/android/pjsua2/src/main/jniLibs/arm64-v8a/libc++_shared.so

OUT="$ROOT/src/main"
rm -rf "$OUT/java/org/pjsip/pjsua2" "$OUT/jniLibs/arm64-v8a"
mkdir -p "$OUT/java/org/pjsip/pjsua2" "$OUT/jniLibs/arm64-v8a"
cp pjsip-apps/src/swig/java/android/pjsua2/src/main/java/org/pjsip/pjsua2/*.java \
  "$OUT/java/org/pjsip/pjsua2/"
cp pjsip-apps/src/swig/java/android/pjsua2/src/main/jniLibs/arm64-v8a/*.so \
  "$OUT/jniLibs/arm64-v8a/"
echo "rebuilt $OUT/jniLibs/arm64-v8a/libpjsua2.so"
