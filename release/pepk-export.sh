#!/usr/bin/env bash
# One-time: encrypts Flyt's app signing key for Play App Signing with Google's PEPK tool.
# Run it yourself after: export BW_SESSION=$(bw unlock --raw)
#   release/pepk-export.sh <pepk.jar> <encryption_public_key.pem>
# The keystore exists only in /dev/shm while PEPK runs. The output zip is encrypted to Google's key.
set -euo pipefail

pepk=${1:?usage: $0 pepk.jar encryption_public_key.pem}
pub=${2:?usage: $0 pepk.jar encryption_public_key.pem}
[[ -t 0 ]] || { echo "Run this in an ordinary terminal; PEPK asks for the passwords." >&2; exit 1; }
[[ "$(bw status | jq -r .status)" == unlocked ]] || { echo 'Unlock first: export BW_SESSION=$(bw unlock --raw)' >&2; exit 1; }

tmp=$(mktemp -d /dev/shm/flyt-pepk.XXXXXX)
trap 'rm -f "$tmp/key.jks"' EXIT
chmod 700 "$tmp"
bw get item "Flyt - Android app signing key" | jq -r .notes | base64 -d > "$tmp/key.jks"

echo "PEPK asks for two passwords: both are the password of «Flyt - Android app signing key» in Bitwarden."
java -jar "$pepk" --keystore="$tmp/key.jks" --alias=flyt-app-signing --output="$tmp/flyt-pepk.zip" \
  --include-cert --rsa-aes-encryption --encryption-key-path="$pub"

echo
echo "Encrypted key for Play: $tmp/flyt-pepk.zip"
echo "Send it to Windows:     flux-cli send --device \"Flux Windows\" $tmp/flyt-pepk.zip"
