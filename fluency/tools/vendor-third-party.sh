#!/usr/bin/env bash
# Copies the parts of the native dependencies that the build needs into third_party/.
# They are vendored (not git submodules) so that the project builds from a plain ZIP download,
# e.g. GitHub's "Download ZIP", in Android Studio without further steps.
#
#   tools/vendor-third-party.sh                 # fetch the pinned commits from GitHub
#   SRC_<NAME>=/path/to/checkout tools/vendor-third-party.sh   # use an existing checkout
#
# To update a dependency: change its commit below, run the script, rebuild, run the tests.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
dst="$here/../third_party"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

#        name               repository                                        commit
deps=(
  "llama.cpp          https://github.com/ggml-org/llama.cpp              abeada335e2e78bd3fe63febafab7e900ce75810"
  "whisper.cpp        https://github.com/ggml-org/whisper.cpp            d1be6fde11ac6e0407606b4e42fe72d34add8037"
  "kleidiai           https://github.com/ARM-software/kleidiai           0b2ee513397578a272a4e8a6c12589ef6534371c"
  "OpenCL-Headers     https://github.com/KhronosGroup/OpenCL-Headers     30bc20a8e90468e231d7c639805ae61ad1fefa4f"
  "OpenCL-ICD-Loader  https://github.com/KhronosGroup/OpenCL-ICD-Loader  5192c84f8059e5f703e5452929b613f9487f6e4c"
)

# what is kept of each checkout (relative paths; directories are copied recursively)
keep_llama=(CMakeLists.txt LICENSE licenses cmake include src ggml vendor)
keep_whisper=(LICENSE include src)
keep_kleidiai=(CMakeLists.txt LICENSES README.md cmake kai third_party)
keep_clheaders=(LICENSE README.md CL)
keep_icd=(CMakeLists.txt LICENSE README.md OpenCL.pc.in cmake inc include loader scripts)

checkout() { # name url commit -> prints directory
  local name=$1 url=$2 commit=$3
  local var="SRC_$(echo "$name" | tr '.-' '__' | tr '[:lower:]' '[:upper:]')"
  if [ -n "${!var:-}" ]; then echo "${!var}"; return; fi
  local d="$work/$name"
  git init -q "$d"
  git -C "$d" fetch -q --depth 1 "$url" "$commit"
  git -C "$d" checkout -q FETCH_HEAD
  echo "$d"
}

for line in "${deps[@]}"; do
  read -r name url commit <<<"$line"
  src="$(checkout "$name" "$url" "$commit")"
  case "$name" in
    llama.cpp) keep=("${keep_llama[@]}") ;;
    whisper.cpp) keep=("${keep_whisper[@]}") ;;
    kleidiai) keep=("${keep_kleidiai[@]}") ;;
    OpenCL-Headers) keep=("${keep_clheaders[@]}") ;;
    OpenCL-ICD-Loader) keep=("${keep_icd[@]}") ;;
  esac
  rm -rf "${dst:?}/$name"
  mkdir -p "$dst/$name"
  for p in "${keep[@]}"; do
    [ -e "$src/$p" ] && cp -a "$src/$p" "$dst/$name/"
  done
  # not needed by the build
  rm -rf "$dst/$name/vendor/cpp-httplib"            # llama.cpp: only for the server
  find "$dst/$name" -name .git -prune -exec rm -rf {} +
  echo "$name $commit $url" > "$dst/$name/VENDORED_FROM"
  echo "vendored $name @ ${commit:0:10}: $(du -sh "$dst/$name" | cut -f1)"
done
