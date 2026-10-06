#!/usr/bin/env bash
# Stores the keystores from release/make-keys.sh base64-encoded in the notes of
# the two Bitwarden Login items (attachments need Premium). Run it yourself:
#   export BW_SESSION=$(bw unlock --raw)
#   release/store-keys.sh /dev/shm/flyt-keys.XXXXXX
set -euo pipefail

dir=${1:?usage: $0 /dev/shm/flyt-keys.XXXXXX}
[[ "$(bw status | jq -r .status)" == unlocked ]] || { echo 'Unlock first: export BW_SESSION=$(bw unlock --raw)' >&2; exit 1; }
bw sync >/dev/null

store() {
  local file="$dir/flyt-$1.jks" name=$2 id
  [[ -f "$file" ]] || { echo "Missing $file" >&2; exit 1; }
  id=$(bw get item "$name" | jq -r .id)
  bw get item "$id" | jq --arg k "$(base64 -w0 "$file")" '.notes = $k' | bw encode | bw edit item "$id" >/dev/null
  # Read back and compare, so a truncated or failed save is noticed.
  cmp -s "$file" <(bw get item "$id" | jq -r .notes | base64 -d) || { echo "Stored key in «$name» does not match $file" >&2; exit 1; }
  echo "OK: $name"
}

store app-signing "Flyt - Android app signing key"
store upload "Flyt - Android upload key"
