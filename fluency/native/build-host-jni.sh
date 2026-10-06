#!/usr/bin/env bash
# Builds the JNI bridge (llama.cpp + whisper.cpp) for the x86-64 host so that the JVM unit tests
# can run the real native code against real models:  native/build/host/jni/libfluency_jni.so
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
build="$here/build/host/jni"
cmake -S "$here/../app/src/main/cpp" -B "$build" -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build "$build" --target fluency_jni -j "${JOBS:-$(nproc)}"
# collect the shared libraries next to each other ($ORIGIN rpath)
find "$build" -name "*.so*" -newer "$here/build-host-jni.sh" -exec cp -P {} "$build/" \; 2>/dev/null || true
ls -la "$build"/*.so*
