#!/usr/bin/env bash
# sheet.sh <out.png> <png...>  — montage of renders, small ones upscaled with hard pixels.
set -euo pipefail
out=$1; shift
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
i=0; args=()
for f in "$@"; do
  w=$(identify -format '%w' "$f")
  if [ "$w" -lt 128 ]; then
    convert "$f" -filter point -resize 128x128 "$tmp/$i.png"
  else
    convert "$f" -resize 256x256\> "$tmp/$i.png"
  fi
  args+=("$tmp/$i.png"); i=$((i+1))
done
montage "${args[@]}" -tile x1 -geometry +12+12 -background '#808080' "$out"
echo "$out"
