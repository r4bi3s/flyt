#!/usr/bin/env bash
# Renders the Play graphics into fastlane/metadata/android/<locale>/images from the HTML templates here.
# Needs Chromium and rsvg-convert. Safe to rerun; it only writes image files.
set -euo pipefail
cd "$(dirname "$0")"
root=$(cd ../.. && pwd)
meta=$root/fastlane/metadata/android
browser=$(command -v chromium-browser || command -v chromium)
# Chromium from snap cannot write to /tmp, so render inside the project build directory.
out=$root/build/store; mkdir -p "$out"

shot() { # width height url output
  "$browser" --headless=new --disable-gpu --no-sandbox --hide-scrollbars --force-device-scale-factor=1 \
    --window-size="$1,$2" --virtual-time-budget=1500 --screenshot="$out/render.png" "$3" >/dev/null 2>&1
  convert "$out/render.png" -crop "$1x$2+0+0" +repage -background '#0f1733' -alpha remove -alpha off "$4"
}
url() { python3 -c 'import sys,urllib.parse as u; print("file://"+sys.argv[1]+"?"+u.urlencode(dict(zip(sys.argv[2::2],sys.argv[3::2]))))' "$@"; }

# Icon: the launcher icon, full bleed (Play applies its own mask).
icon=$out/icon.svg
cat > "$icon" <<'SVG'
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 48 48"><rect width="48" height="48" fill="#101d25"/><circle cx="24" cy="24" r="16" fill="none" stroke="#68d9d0" stroke-width="2.2"/><circle cx="24" cy="24" r="10" fill="none" stroke="#31545c" stroke-width="1.3"/><circle cx="34.5" cy="11" r="2.6" fill="#e9aa7e"/><rect x="21.5" y="21.5" width="5" height="5" fill="#68d9d0"/></svg>
SVG

render_locale() { # locale, then title/sub pairs for: feature, radial, search, apps, tag
  local loc=$1; shift
  local img=$meta/$loc/images; mkdir -p "$img/phoneScreenshots"
  rsvg-convert -w 512 -h 512 "$icon" | convert - -alpha off "$img/icon.png"
  shot 1024 500 "$(url "$PWD/feature.html" title "$1" sub "$2")" "$img/featureGraphic.png"; shift 2
  local n=1
  for src in radial search apps tag; do
    shot 1080 1920 "$(url "$PWD/screenshot.html" img "src/$src.jpg" title "$1" sub "$2")" "$img/phoneScreenshots/${n}_$loc.png"
    shift 2; n=$((n + 1))
  done
}

render_locale en-US \
  "Muscle memory for your *phone*." "A calm, one-handed Android launcher." \
  "Drag toward a tag. Its apps *unfold*." "Let go on the one you want." \
  "Search finds *everything else*." "Apps, contacts, actions and shortcuts." \
  "Your tags, *under your thumb*." "Local, overlapping, yours." \
  "One tag, *every* app it holds." "Apps and actions in as many tags as you like."

render_locale no-NO \
  "Muskelminne for *telefonen*." "En rolig Android-launcher for én hånd." \
  "Dra mot en tagg. Appene *folder seg ut*." "Slipp på den du vil ha." \
  "Søk finner *resten*." "Apper, kontakter, handlinger og snarveier." \
  "Taggene dine, *under tommelen*." "Lokale, overlappende og dine." \
  "Én tagg, *alle* appene i den." "Apper og handlinger i så mange tagger du vil."

rm -f "$out/render.png"
find "$meta" -name '*.png' | sort
