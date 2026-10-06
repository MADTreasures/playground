#!/usr/bin/env bash
# Builds libsherpa-onnx-jni.so (VAD, offline ASR, TTS) from source.
#
#   native/build-sherpa-onnx.sh android   -> app/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so
#   native/build-sherpa-onnx.sh host      -> native/build/host/sherpa/lib (x86_64 Linux, for JVM tests)
#
# Inputs (env, with defaults matching the dev container):
#   SHERPA_SRC   sherpa-onnx source checkout (tag v1.13.8)
#   DEPS_DIR     pre-fetched FetchContent sources (kaldi-decoder, kaldifst, kissfft, eigen, openfst,
#                espeak-ng, piper-phonemize)
#   ORT_AAR_DIR  unpacked com.microsoft.onnxruntime:onnxruntime-android:1.28.0 AAR (headers/ + jni/)
#   ORT_JAR_DIR  unpacked com.microsoft.onnxruntime:onnxruntime:1.28.0 JAR (linux-x64 lib, host only)
#   ANDROID_NDK  NDK root
# The tarballs for kaldi-native-fbank, simple-sentencepiece and json are taken from ~/Downloads
# (sherpa-onnx looks there first), the rest (incl. kissfft) via FETCHCONTENT_SOURCE_DIR_* overrides, so the build
# works without access to GitHub release downloads.
set -euo pipefail
target="${1:-android}"
here="$(cd "$(dirname "$0")" && pwd)"
app_dir="$(cd "$here/.." && pwd)"
SHERPA_SRC="${SHERPA_SRC:-/home/user/src/sherpa-onnx}"
DEPS_DIR="${DEPS_DIR:-/home/user/src/sherpa-deps}"
ORT_AAR_DIR="${ORT_AAR_DIR:-/home/user/src/ort/android-1.28.0}"
ORT_JAR_DIR="${ORT_JAR_DIR:-/home/user/src/ort/jar-1.28.0}"
ANDROID_NDK="${ANDROID_NDK:-/opt/android-sdk/ndk/30.0.16248370}"
jobs="${JOBS:-$(nproc)}"

common_args=(
  -DCMAKE_BUILD_TYPE=Release
  -DBUILD_SHARED_LIBS=ON
  -DSHERPA_ONNX_ENABLE_PYTHON=OFF
  -DSHERPA_ONNX_ENABLE_TESTS=OFF
  -DSHERPA_ONNX_ENABLE_CHECK=OFF
  -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF
  -DSHERPA_ONNX_ENABLE_JNI=ON
  -DSHERPA_ONNX_ENABLE_C_API=OFF
  -DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF
  -DSHERPA_ONNX_ENABLE_BINARY=OFF
  -DSHERPA_ONNX_ENABLE_TTS=ON
  -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF
  -DSHERPA_ONNX_ENABLE_RKNN=OFF
  -DSHERPA_ONNX_ENABLE_QNN=OFF
  -DSHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY=OFF
  -DBUILD_PIPER_PHONMIZE_EXE=OFF
  -DBUILD_PIPER_PHONMIZE_TESTS=OFF
  -DBUILD_ESPEAK_NG_EXE=OFF
  -DBUILD_ESPEAK_NG_TESTS=OFF
  -DFETCHCONTENT_SOURCE_DIR_KALDI_DECODER="$DEPS_DIR/kaldi-decoder"
  -DFETCHCONTENT_SOURCE_DIR_EIGEN="$DEPS_DIR/eigen"
  -DFETCHCONTENT_SOURCE_DIR_OPENFST="$DEPS_DIR/openfst"
  -DFETCHCONTENT_SOURCE_DIR_ESPEAK_NG="$DEPS_DIR/espeak-ng"
  -DFETCHCONTENT_SOURCE_DIR_PIPER_PHONEMIZE="$DEPS_DIR/piper-phonemize"
  -DFETCHCONTENT_SOURCE_DIR_KISSFFT="$DEPS_DIR/kissfft"
  -DFETCHCONTENT_SOURCE_DIR_KALDIFST="$DEPS_DIR/kaldifst"
)

case "$target" in
  android)
    build="$here/build/android-arm64/sherpa"
    export SHERPA_ONNXRUNTIME_LIB_DIR="$ORT_AAR_DIR/jni/arm64-v8a"
    export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$ORT_AAR_DIR/headers"
    cmake -S "$SHERPA_SRC" -B "$build" -G Ninja "${common_args[@]}" \
      -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 \
      -DCMAKE_SHARED_LINKER_FLAGS="-Wl,-z,max-page-size=16384 -Wl,--gc-sections" \
      -DCMAKE_C_FLAGS="-ffunction-sections -fdata-sections" \
      -DCMAKE_CXX_FLAGS="-ffunction-sections -fdata-sections"
    cmake --build "$build" --target sherpa-onnx-jni -j "$jobs"
    out="$app_dir/app/src/main/jniLibs/arm64-v8a"
    mkdir -p "$out"
    "$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded \
      -o "$out/libsherpa-onnx-jni.so" "$build/lib/libsherpa-onnx-jni.so"
    ls -la "$out"
    ;;
  host)
    build="$here/build/host/sherpa"
    mkdir -p "$build/ort"
    cp -f "$ORT_JAR_DIR/ai/onnxruntime/native/linux-x64/libonnxruntime.so" "$build/ort/"
    export SHERPA_ONNXRUNTIME_LIB_DIR="$build/ort"
    export SHERPA_ONNXRUNTIME_INCLUDE_DIR="$ORT_AAR_DIR/headers"
    cmake -S "$SHERPA_SRC" -B "$build" -G Ninja "${common_args[@]}" \
      -DCMAKE_INSTALL_RPATH='$ORIGIN' -DCMAKE_BUILD_WITH_INSTALL_RPATH=ON
    cmake --build "$build" --target sherpa-onnx-jni -j "$jobs"
    cp -f "$build/ort/libonnxruntime.so" "$build/lib/"
    # the desktop build's SONAME is libonnxruntime.so.1
    ln -sf libonnxruntime.so "$build/lib/libonnxruntime.so.1"
    ls -la "$build/lib"
    ;;
  *) echo "unknown target $target"; exit 1 ;;
esac
