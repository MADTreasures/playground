#!/usr/bin/env bash
# Packs the Android Studio project (with the vendored native sources) into
#   ../dist/Fluency-AndroidStudio-<versionName>.zip   (top-level folder: Fluency/)
# Unzip, open the "Fluency" folder in Android Studio, build. No submodules, no extra downloads
# except what Gradle/Android Studio fetch themselves (Gradle, AGP, Maven libraries, SDK/NDK).
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
version="$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' "$here/app/build.gradle.kts")"
out_dir="$(cd "$here/.." && pwd)/dist"
out="$out_dir/Fluency-AndroidStudio-$version.zip"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
mkdir -p "$out_dir" "$stage/Fluency"
# copy the project without build outputs, IDE state or secrets
tar -C "$here" \
  --exclude='./.gradle' --exclude='./build' --exclude='./app/build' --exclude='./app/.cxx' \
  --exclude='./native/build' --exclude='./.idea' --exclude='./.kotlin' --exclude='./captures' \
  --exclude='./local.properties' --exclude='./keystore.properties' --exclude='*.jks' --exclude='*.keystore' \
  --exclude='./test-models' \
  -cf - . | tar -C "$stage/Fluency" -xf -
rm -f "$out"
(cd "$stage" && zip -q -r -X -9 "$out" Fluency)
echo "$out: $(du -h "$out" | cut -f1), $(unzip -l "$out" | tail -1 | awk '{print $2}') files"
