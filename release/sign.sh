#!/usr/bin/env bash
# Signs an unsigned release build with a key fetched from Bitwarden.
# Run it yourself after: export BW_SESSION=$(bw unlock --raw)
#
#   release/sign.sh apk   GitHub APK, app signing key  -> dist/flyt-<version>.apk + .sha256
#   release/sign.sh aab   Play bundle, upload key      -> dist/flyt-<version>.aab
#
# Build first: ./gradlew :app:assembleRelease :app:bundleRelease
# The keystore exists only in /dev/shm while signing and is removed afterwards.
set -euo pipefail

kind=${1:-}
root=$(cd "$(dirname "$0")/.." && pwd)
case "$kind" in
  apk) item="Lunni Flyt - Android app signing key"; input="$root/app/build/outputs/apk/release/app-release-unsigned.apk" ;;
  aab) item="Lunni Flyt - Android upload key";      input="$root/app/build/outputs/bundle/release/app-release.aab" ;;
  *) echo "usage: $0 apk|aab" >&2; exit 2 ;;
esac
[[ -f "$input" ]] || { echo "Missing $input. Build the release first." >&2; exit 1; }
[[ "$(bw status | jq -r .status)" == unlocked ]] || { echo 'Unlock first: export BW_SESSION=$(bw unlock --raw)' >&2; exit 1; }

sdk=${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' "$root/local.properties")}
tools=$(ls -d "$sdk"/build-tools/* | sort -V | tail -1)
version=$(sed -n 's/^ *versionName = "\(.*\)"/\1/p' "$root/app/build.gradle.kts")

tmp=$(mktemp -d /dev/shm/flyt-sign.XXXXXX)
trap 'rm -rf "$tmp"' EXIT
chmod 700 "$tmp"

json=$(bw get item "$item")
id=$(jq -r .id <<<"$json")
alias=$(jq -r .login.username <<<"$json")
attachment=$(jq -r '.attachments[0].fileName' <<<"$json")
bw get attachment "$attachment" --itemid "$id" --output "$tmp/key.jks" >/dev/null
FLYT_KS_PASS=$(jq -r .login.password <<<"$json")
export FLYT_KS_PASS
unset json

mkdir -p "$root/dist"
if [[ $kind == apk ]]; then
  out="$root/dist/flyt-$version.apk"
  "$tools/zipalign" -P 16 -f 4 "$input" "$tmp/aligned.apk"
  "$tools/apksigner" sign --ks "$tmp/key.jks" --ks-key-alias "$alias" --ks-pass env:FLYT_KS_PASS \
    --out "$out" "$tmp/aligned.apk"
  rm -f "$out.idsig"
  "$tools/apksigner" verify --print-certs "$out" | grep -E 'Signer #1 certificate (DN|SHA-256)'
  (cd "$root/dist" && sha256sum "$(basename "$out")" > "$(basename "$out").sha256" && cat "$(basename "$out").sha256")
else
  out="$root/dist/flyt-$version.aab"
  cp "$input" "$out"
  jarsigner -keystore "$tmp/key.jks" -storepass:env FLYT_KS_PASS "$out" "$alias" >/dev/null
  jarsigner -verify "$out" | grep -m1 "jar verified"
fi
echo "Signed: $out"
