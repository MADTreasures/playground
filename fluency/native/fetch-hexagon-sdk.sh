#!/usr/bin/env bash
# Fetches Qualcomm's Hexagon SDK (needed only to rebuild the NPU backend, see build-hexagon.sh).
#
# The SDK is taken from llama.cpp's Snapdragon toolchain image (ghcr.io/snapdragon-toolchain/
# arm64-android:v0.7, the image llama.cpp's own Snapdragon builds use). Only the image layer that
# contains /opt/hexagon/6.6.0.0 is downloaded (1.1 GB) - no Docker needed - and checked against
# its digest.
#
#   native/fetch-hexagon-sdk.sh [target-dir]      # default: ~/hexagon  ->  ~/hexagon/6.6.0.0
set -euo pipefail
dest="${1:-$HOME/hexagon}"
image="snapdragon-toolchain/arm64-android"
# layer of v0.7 (linux/amd64) that holds the Hexagon SDK 6.6.0.0 (Hexagon Tools 19.0.07)
layer="sha256:bfa19e54cc1c852685eb4cd8c680d8575c54c0cc589843df5324dd7b721ef389"

if [ -f "$dest/6.6.0.0/hexagon_sdk.json" ]; then
  echo "Hexagon SDK already in $dest/6.6.0.0"
  exit 0
fi
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
token="$(curl -fsS "https://ghcr.io/token?scope=repository:$image:pull" | python3 -I -c 'import json,sys; print(json.load(sys.stdin)["token"])')"
echo "downloading the Hexagon SDK layer (1.1 GB) ..."
curl -fSL -H "Authorization: Bearer $token" "https://ghcr.io/v2/$image/blobs/$layer" -o "$work/layer.tar.gz"
echo "${layer#sha256:}  $work/layer.tar.gz" | sha256sum -c -
mkdir -p "$dest"
tar -xzf "$work/layer.tar.gz" -C "$dest" --strip-components=2 opt/hexagon/6.6.0.0
echo "Hexagon SDK: $dest/6.6.0.0"
