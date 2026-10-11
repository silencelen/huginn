#!/usr/bin/env bash
# regen.sh [mark.svg|mark.png]   — rebuild the whole ASCII set.
#
#   1. sample the mark into gen/ (raw pipeline output + '#' grids) for 01, 02 and 03,
#      and the 80-column shaded poster into 01/extras
#   2. pack the hand-tuned src/ grids into the shipped .txt art; pad the hand-written
#      ASCII files to equal width
#   3. compose the lockups and banners from the shipped art
#   4. render every preview.png (10x18 px cells = a 16px terminal; even width, see preview.py)
#   5. check.py on every shipped .txt (asciicheck, 7-bit for *ascii*, tmux round trip)
#
#   regen.sh --pack   skips step 1 (re-pack src/, recompose, re-render only)
#
# When the mark changes: run with the new mark, diff gen/*.grid against src/*.grid,
# port the hand-tuning (eye, bill taper, crumbs) into src/, run again with --pack.
# The ASCII ravens (03/raven-*ascii*.txt, 04/*-ascii.txt) are typed by hand, not sampled.
set -euo pipefail
cd "$(dirname "$0")"
MARK=${1:-../../raven.svg}
L=ascii-01-portrait-large; M=ascii-02-portrait-medium; C=ascii-03-compact; K=ascii-04-lockups; R=ascii-05-runes
G() { python3 svg2ascii.py "$@"; }
P() { python3 preview.py "$@"; }
TAG="Thought, on call from anywhere."

if [ "${1:-}" != "--pack" ]; then
  echo "== 1. sampling $MARK"
  mkdir -p $L/gen $L/extras $M/gen $C/gen
  for st in half braille quarter shaded; do
    G "$MARK" --style $st --width 48 --out $L/gen/$st.txt
    G "$MARK" --style $st --width 48 --grid --out $L/gen/$st.grid
  done
  G "$MARK" --style shaded --width 80 --out $L/extras/raven-80-shaded.txt
  G "$MARK" --style half --width 24 --out $M/gen/half.txt
  G "$MARK" --style half --width 24 --grid --out $M/gen/half.grid
  G "$MARK" --style braille --width 24 --threshold 0.45 --out $M/gen/braille.txt
  G "$MARK" --style braille --width 24 --threshold 0.45 --grid --out $M/gen/braille.grid
  for spec in "half 2 13" "half 3 15" "half 5 20" "braille 2 8" "braille 3 10" "braille 5 16" "quarter 2 9"; do
    set -- $spec
    G "$MARK" --style $1 --rows $2 --width $3 --threshold 0.45 --out $C/gen/$1-$2line.txt
    G "$MARK" --style $1 --rows $2 --width $3 --threshold 0.45 --grid --out $C/gen/$1-$2line.grid
  done
fi

echo "== 2. packing src/ grids"
G $L/src/half-48.grid --style half --out $L/raven-48-half.txt
G $L/src/braille-48.grid --style braille --out $L/raven-48-braille.txt
G $L/src/shaded-48.grid --style shaded --out $L/extras/raven-48-shaded.txt
G $M/src/half-24.grid --style half --out $M/raven-24-half.txt
G $M/src/braille-24.grid --style braille --out $M/raven-24-braille.txt
G $C/src/l2-quarter.grid --style quarter --out $C/raven-2line-a-quarter.txt
G $C/src/l2-braille.grid --style braille --out $C/raven-2line-b-braille.txt
G $C/src/l2-half.grid --style half --out $C/raven-2line-c-half.txt
G $C/src/l3-half-a.grid --style half --out $C/raven-3line-a-half.txt
G $C/src/l3-braille.grid --style braille --out $C/raven-3line-b-braille.txt
G $C/src/l3-half-b.grid --style half --out $C/raven-3line-c-half.txt
G $C/src/l5-half-a.grid --style half --out $C/raven-5line-a-half.txt
G $C/src/l5-braille.grid --style braille --out $C/raven-5line-b-braille.txt
G $C/src/l5-half-b.grid --style half --out $C/raven-5line-c-half.txt

echo "== 3. lockups"
python3 lockup.py --raven $C/raven-5line-a-half.txt --font ansi_shadow --gap 3 --tagline "$TAG" --out $K/lockup-a-ansi-shadow.txt
python3 lockup.py --raven $C/raven-3line-a-half.txt --font calvin_s --gap 3 --tagline "$TAG" --out $K/lockup-b-calvin.txt
python3 lockup.py --raven $M/raven-24-half.txt --font pagga --stack --gap 1 --tagline "$TAG" --out $K/lockup-c-pagga-stacked.txt
python3 lockup.py --raven $M/raven-24-half.txt --font ansi_shadow --gap 3 --tagline "$TAG" --out $K/lockup-d-readme-hero.txt
python3 lockup.py --raven $C/raven-4line-ascii-a.txt --font small --gap 3 --tagline "$TAG" --out $K/lockup-e-ascii.txt
G1=$(cat $C/raven-1line-a.txt); G2=$(cat $C/raven-1line-b.txt); A1=$(cat $C/raven-1line-ascii.txt)
printf '%s huginn · %s\n' "$G1" "$TAG" > $K/banner.txt
printf '%s huginn - %s\n' "$A1" "$TAG" > $K/banner-ascii.txt
printf '%s huginn · %s\n%s huginn · %s \n%s huginn - %s\n' "$G1" "$TAG" "$G2" "$TAG" "$A1" "$TAG" > $K/banner-variants.txt
printf '%s main\n' "$G1" > $K/tmux-status-left.txt
# hand-written files: equal width, space padded
python3 - $C/raven-*ascii*.txt $K/help-header-ascii.txt $K/banner-variants.txt $R/example-*.txt <<'PY'
import sys
for p in sys.argv[1:]:
    lines = open(p, encoding='utf-8').read().rstrip('\n').split('\n')
    w = max(len(l.rstrip()) for l in lines)
    open(p, 'w', encoding='utf-8').write('\n'.join(l.rstrip().ljust(w) for l in lines) + '\n')
PY

echo "== 4. previews"
P --out $L/preview.png --width 1080 $L/raven-48-half.txt:"half-block 48" $L/raven-48-braille.txt:"braille 48 (hand-tuned)"
P --out $L/extras/preview-shaded.png --width 1080 --canvas 2400 $L/extras/raven-80-shaded.txt:"shaded 80 (poster / dither source, not a candidate)" $L/extras/raven-48-shaded.txt:"shaded 48 (hand-tuned; too noisy)"
P --out $M/preview.png --width 700 $M/raven-24-half.txt:"half-block 24 (hand-tuned)" $M/raven-24-braille.txt:"braille 24 (hand-tuned)"
P --out $M/preview-fontdrawn.png --width 700 --font-blocks --grounds dark --cell 10x18 $M/raven-24-half.txt:"font-drawn blocks, 10x18 (xterm-like)" \
  $M/raven-24-braille.txt:"braille, 10x18"
P --out $M/preview-github.png --width 700 --font-blocks --grounds light --cell 10x24 $M/raven-24-half.txt:"font-drawn, line-height 1.45 (GitHub code view): stripes" \
  $M/raven-24-braille.txt:"braille at line-height 1.45: survives"
COMPACT=( $C/raven-1line-a.txt:"1-line a ◢▆█▀ (glyph, not a raven)" $C/raven-1line-a-fallback.txt:"1-line a fallback ▗▆█▀" $C/raven-1line-b.txt:"1-line b ◢▆▀" $C/raven-1line-c.txt:"1-line c ◢●▸" \
  $C/raven-1line-ascii.txt:"1-line ASCII (o)=-" $C/raven-1line-ascii-b.txt:"1-line ASCII b ~(o)=-" \
  $C/raven-2line-a-quarter.txt:"2-line a quarter" $C/raven-2line-b-braille.txt:"2-line b braille" $C/raven-2line-c-half.txt:"2-line c half" \
  $C/raven-3line-a-half.txt:"3-line a half" $C/raven-3line-b-braille.txt:"3-line b braille" $C/raven-3line-c-half.txt:"3-line c half (narrow)" $C/raven-3line-ascii.txt:"3-line ASCII" \
  $C/raven-4line-ascii-a.txt:"4-line ASCII a (help raven successor)" $C/raven-4line-ascii-b.txt:"4-line ASCII b" \
  $C/raven-5line-a-half.txt:"5-line a half" $C/raven-5line-b-braille.txt:"5-line b braille" $C/raven-5line-c-half.txt:"5-line c half (narrow)" )
P --out $C/preview.png --width 980 "${COMPACT[@]}"
P --out $C/preview-zoom.png --cell 20x36 --width 1400 --grounds dark --canvas 2400 "${COMPACT[@]}"
P --out $K/preview.png --width 980 --canvas 3000 \
  $K/lockup-a-ansi-shadow.txt:"a  5-line raven + ANSI Shadow (server-side splash)" $K/lockup-b-calvin.txt:"b  3-line raven + calvin_s (slim)" \
  $K/lockup-c-pagga-stacked.txt:"c  24-col raven over pagga (stacked)" $K/lockup-d-readme-hero.txt:"d  24-col raven + ANSI Shadow (README hero, 76 cols)" \
  $K/lockup-e-ascii.txt:"e  ASCII raven + figlet small (7-bit, client files)" $K/help-header-ascii.txt:"huginn --help header (7-bit, drop-in for huginn.sh / huginn.ps1)" \
  $K/banner-variants.txt:"first line: Unicode (server) / ASCII (client)"
P --out $K/preview-mocks.png --width 980 --grounds dark \
  $K/tmux-status-mock.html:"tmux status-left: quiet default / gold = needs you / 3-cell glyph" $K/splash-mock.html:"splash: server-side after attach (Unicode) and client before ssh (ASCII)"
P --out $R/preview.png --width 980 --canvas 2400 \
  $R/example-a.txt:"A  Ansuz as status bullet" $R/example-b.txt:"B  Hagalaz as section mark" \
  $R/example-c.txt:"C  runic punctuation as separators" $R/example-d.txt:"D  plain alternatives" \
  $R/example-e.txt:"E  state glyphs: working / needs you / waiting / no claude" $R/state-mock.html:"E  tab titles: today vs candidates"

echo "== 5. check"
python3 check.py */*.txt */extras/*.txt
for f in */preview*.png */extras/preview*.png; do s=$(stat -c %s "$f"); [ "$s" -lt 400000 ] || echo "WARNING $f is $s bytes"; done
echo done
