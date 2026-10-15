#!/usr/bin/env bash
# Regenerate the tray icons from the brand's raven path (assets/brand/raven-tile.svg):
# the raven CUT OUT of a rounded tile, tinted by state (BRAND.md §2 state ladder,
# the same three tints as the desktop's TrayIcons.kt), plus the error-red badge
# dot on needs-you — the cue that still reads at 16 px on a monochrome tray.
# Output: idle.png working.png attention.png (64 px), embedded by tray.go.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../../../../assets/brand/raven-tile.svg"
# The raven path + its transform, lifted from the master so the two cannot drift.
G="$(sed -n '/<g transform/,/<\/g>/p' "$SRC" | sed 's/fill="#141615"/fill="#000"/')"
mk() { # $1 name  $2 tint  $3 badge(0|1)
  {
    echo '<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 108 108">'
    echo '<defs><mask id="m"><rect width="108" height="108" fill="#fff"/>'"$G"'</mask></defs>'
    echo "<rect width=\"108\" height=\"108\" rx=\"24\" fill=\"$2\" mask=\"url(#m)\"/>"
    [ "$3" = 1 ] && echo '<circle cx="89.4" cy="18.6" r="15.2" fill="#EE757D"/>'
    echo '</svg>'
  } > "$HERE/$1.svg"
  rsvg-convert -w 64 -h 64 "$HERE/$1.svg" -o "$HERE/$1.png"
  rm -f "$HERE/$1.svg"
}
mk idle '#848C87' 0
mk working '#7CB98E' 0
mk attention '#E8D07E' 1
ls -la "$HERE"/*.png
