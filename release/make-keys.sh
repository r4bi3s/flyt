#!/usr/bin/env bash
# Creates Flyt's app signing key and upload key once, in memory (/dev/shm).
# Run this yourself; keytool asks for the passwords, so none are printed.
# Afterwards store both keystores in Bitwarden as described in RELEASING.md.
set -euo pipefail

[[ -t 0 ]] || { echo "Run this in an ordinary terminal; keytool must read the passwords from you." >&2; exit 1; }

out=$(mktemp -d /dev/shm/flyt-keys.XXXXXX)
chmod 700 "$out"
trap '[[ -f "$out/flyt-upload.jks" ]] || rm -rf "$out"' EXIT
dname="CN=Flyt, O=Heimlager, C=NO"

for key in app-signing upload; do
  echo "== $key key: choose a strong password (generate it in Bitwarden)"
  keytool -genkeypair -keystore "$out/flyt-$key.jks" -storetype PKCS12 -alias "flyt-$key" \
    -keyalg RSA -keysize 4096 -validity 36500 -dname "$dname"
  [[ -f "$out/flyt-$key.jks" ]] || { echo "No $key keystore was created; nothing kept." >&2; exit 1; }
done

mkdir -p "$HOME/lunni-keys"
for key in app-signing upload; do
  echo "== export public certificate for $key (asks the $key password)"
  keytool -exportcert -rfc -keystore "$out/flyt-$key.jks" -alias "flyt-$key" -file "$HOME/lunni-keys/flyt-$key.pem"
done

cat <<EOF

Keystores are in $out (memory only, gone after reboot):
  flyt-app-signing.jks  alias flyt-app-signing
  flyt-upload.jks       alias flyt-upload
Public certificates: ~/lunni-keys/flyt-app-signing.pem, ~/lunni-keys/flyt-upload.pem

Next (RELEASING.md, "One-time setup"):
  1. In Bitwarden (any folder), create Login items «Flyt - Android app signing key» and
     «Flyt - Android upload key». Username = alias, password = keystore
     password, attach the .jks file.
  2. Copy flyt-app-signing.jks to the offline backup.
  3. Encrypt the app signing key for Play with PEPK when creating the app.
  4. Then delete the folder: rm -rf $out
EOF
