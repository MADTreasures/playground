#!/usr/bin/env bash
# Builds llama.cpp's Hexagon NPU backend for the app and copies it into app/src/main/jniLibs:
#
#   libggml-hexagon.so        ggml backend (arm64, loaded by the app like libggml-opencl.so)
#   libggml-htp-v73.so ...    the programs that run on the NPU itself, one per Hexagon version:
#   libggml-htp-v81.so        v73 = 8 Gen 2, v75 = 8 Gen 3, v79 = 8 Elite, v81 = 8 Elite Gen 5
#
# The app build (Gradle / Android Studio) does not need the Hexagon SDK: it packages these
# prebuilt files. Rebuild them whenever llama.cpp in third_party/ changes - libggml-hexagon.so
# links against the app's libggml-base.so and must come from the same source.
#
#   native/fetch-hexagon-sdk.sh                       # once: Hexagon SDK -> ~/hexagon/6.6.0.0
#   HEXAGON_SDK_ROOT=~/hexagon/6.6.0.0 native/build-hexagon.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
app="$here/../app"
sdk="${HEXAGON_SDK_ROOT:-$HOME/hexagon/6.6.0.0}"
tools="${HEXAGON_TOOLS_ROOT:-$sdk/tools/HEXAGON_Tools/19.0.07}"
android_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
ndk="${ANDROID_NDK_ROOT:-$android_sdk/ndk/30.0.16248370}"   # same NDK as app/build.gradle.kts
cmake_bin="${CMAKE_BIN:-$android_sdk/cmake/3.31.6/bin}"
build="$here/build/android-hexagon"
out="$app/src/main/jniLibs/arm64-v8a"

[ -f "$sdk/hexagon_sdk.json" ] || { echo "Hexagon SDK not found in $sdk (run native/fetch-hexagon-sdk.sh)"; exit 1; }

# same configuration as the app's externalNativeBuild (app/build.gradle.kts), plus the NPU backend
"$cmake_bin/cmake" -S "$app/src/main/cpp" -B "$build" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$cmake_bin/ninja" \
  -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  -DFLUENCY_HEXAGON=ON -DHEXAGON_SDK_ROOT="$sdk" -DHEXAGON_TOOLS_ROOT="$tools" -DPREBUILT_LIB_DIR=android_aarch64
"$cmake_bin/cmake" --build "$build" --target ggml-hexagon htp-v73 htp-v75 htp-v79 htp-v81 -j "${JOBS:-$(nproc)}"

strip="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
hexstrip="$tools/Tools/bin/hexagon-llvm-strip"
mkdir -p "$out"
cp "$build/bin/libggml-hexagon.so" "$out/"
"$strip" --strip-unneeded "$out/libggml-hexagon.so"
for v in v73 v75 v79 v81; do
  cp "$build/ll/ggml/src/ggml-hexagon/libggml-htp-$v.so" "$out/"
  [ -x "$hexstrip" ] && "$hexstrip" --strip-unneeded "$out/libggml-htp-$v.so"
done
commit="$(cut -d' ' -f2 "$here/../third_party/llama.cpp/VENDORED_FROM")"
echo "llama.cpp $commit, Hexagon SDK $(basename "$sdk"), Hexagon Tools $(basename "$tools")" > "$here/HEXAGON_BUILT_FROM"
ls -la "$out"
