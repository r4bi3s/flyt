#!/usr/bin/env bash
# Renders a short vertical video (1080×1920, ~15 s, no sound) for social posts into build/store/flyt-social.mp4:
# title card, the radial clip, the search clip, the Apps screen and an end card, captions burned in.
# Needs Chromium, ImageMagick and ffmpeg. Reuses screenshot.html (phone frame) and card.html.
set -euo pipefail
cd "$(dirname "$0")"
root=$(cd ../.. && pwd)
browser=$(command -v chromium-browser || command -v chromium)
out=$root/build/store; mkdir -p "$out"
url() { python3 -c 'import sys,urllib.parse as u; print("file://"+sys.argv[1]+"?"+u.urlencode(dict(zip(sys.argv[2::2],sys.argv[3::2]))))' "$@"; }
png() { # url output
  "$browser" --headless=new --disable-gpu --no-sandbox --hide-scrollbars --force-device-scale-factor=1 \
    --window-size=1080,1920 --virtual-time-budget=1500 --screenshot="$out/render.png" "$1" >/dev/null 2>&1
  convert "$out/render.png" -crop 1080x1920+0+0 +repage "$2"
}
# A caption frame whose phone screen is a transparent hole (screenshot.html: 690×1374 image at 195,460, radius 50).
frame() { # title sub output
  png "$(url "$PWD/screenshot.html" img "" title "$1" sub "$2")" "$out/f.png"
  convert "$out/f.png" \( +clone -alpha extract -fill black -draw "roundrectangle 195,460 884,1833 50,50" \) \
    -alpha off -compose copy_opacity -composite "$3"
}

png "$(url "$PWD/card.html" title "Muscle memory for your *phone*." sub "A calm, one-handed Android launcher.")" "$out/c-title.png"
png "$(url "$PWD/card.html" title "Drag. Let go. *Done.*" sub "No account. No ads. No tracking." foot "Free & open source · r4bi3s.github.io/flyt")" "$out/c-end.png"
frame "Drag toward a tag. Its apps *unfold*." "Let go on the one you want." "$out/f-radial.png"
frame "Search finds *everything else*." "Apps, contacts, actions and shortcuts." "$out/f-search.png"
frame "Your tags, *under your thumb*." "Local, overlapping, yours." "$out/f-apps.png"

# Segment lengths (s); clips keep their own length. Each later segment fades in over 0.35 s.
T=2.2; R=$(ffprobe -v error -show_entries format=duration -of csv=p=0 src/radial.mp4); Q=$(ffprobe -v error -show_entries format=duration -of csv=p=0 src/search.mp4); A=2.4; E=3.0; X=0.35
o1=$(python3 -c "print($T-$X)"); o2=$(python3 -c "print($T+$R-2*$X)"); o3=$(python3 -c "print($T+$R+$Q-3*$X)"); o4=$(python3 -c "print($T+$R+$Q+$A-4*$X)")

ffmpeg -v error -y \
  -loop 1 -t $T -framerate 30 -i "$out/c-title.png" \
  -i src/radial.mp4 -loop 1 -t "$R" -framerate 30 -i "$out/f-radial.png" \
  -i src/search.mp4 -loop 1 -t "$Q" -framerate 30 -i "$out/f-search.png" \
  -loop 1 -t $A -framerate 30 -i src/apps.jpg -loop 1 -t $A -framerate 30 -i "$out/f-apps.png" \
  -loop 1 -t $E -framerate 30 -i "$out/c-end.png" \
  -filter_complex "
    color=c=0x0f1733:s=1080x1920:r=30[bg];
    [0:v]format=yuv420p,setsar=1,fps=30,settb=1/30[s0];
    [bg]split=3[b1][b2][b3];
    [b1]trim=duration=$R[b1t]; [b1t][1:v]overlay=195:460:shortest=1[v1]; [v1][2:v]overlay=0:0:shortest=1,format=yuv420p,setsar=1,fps=30,settb=1/30[s1];
    [b2]trim=duration=$Q[b2t]; [b2t][3:v]overlay=195:460:shortest=1[v2]; [v2][4:v]overlay=0:0:shortest=1,format=yuv420p,setsar=1,fps=30,settb=1/30[s2];
    [5:v]scale=690:1374[ap]; [b3]trim=duration=$A[b3t]; [b3t][ap]overlay=195:460:shortest=1[v3]; [v3][6:v]overlay=0:0:shortest=1,format=yuv420p,setsar=1,fps=30,settb=1/30[s3];
    [7:v]format=yuv420p,setsar=1,fps=30,settb=1/30[s4];
    [s0][s1]xfade=transition=fade:duration=$X:offset=$o1[x1];
    [x1][s2]xfade=transition=fade:duration=$X:offset=$o2[x2];
    [x2][s3]xfade=transition=fade:duration=$X:offset=$o3[x3];
    [x3][s4]xfade=transition=fade:duration=$X:offset=$o4,format=yuv420p[v]" \
  -map "[v]" -c:v libx264 -preset slow -crf 20 -pix_fmt yuv420p -movflags +faststart "$out/flyt-social.mp4"
rm -f "$out/render.png" "$out/f.png"
ffprobe -v error -show_entries format=duration:stream=width,height -of compact "$out/flyt-social.mp4"
