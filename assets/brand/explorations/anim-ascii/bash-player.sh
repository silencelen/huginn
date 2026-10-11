#!/usr/bin/env bash
# huginn_play FRAMES.txt|- [LOOPS] [FPS]
#
# Plays a huginn-frames file (format: anim-ascii/README.md). Line 1 of the file is a header
#   # huginn-frames cols=W rows=H fps=N loop=yes|no rest=first|last|none [holds=ms,ms,...] [pad=X]
# followed by frames separated by a line containing only "---". Trailing padding in a frame
# line is the pad character (default U+2800, ASCII twins use "@"); it is printed as a space.
#
# Pure 7-bit ASCII and bash builtins only (no tput, no sleep, no awk): it can be pasted into
# client/huginn.sh as is, and it runs on Termux, which ships no tput. "-" reads the frames from
# stdin, so a quoted heredoc inside huginn.sh works: huginn_play - <<'EOF' ... EOF
#
# Static fallback (no escape codes, trailing blanks trimmed): not a tty, TERM=dumb, HUGINN_NO_ANIM
# set, or the terminal is narrower than the frame -> the rest frame is printed once (rest=none
# prints nothing). LOOPS caps the number of passes (0 = as the header says: once, or forever
# for loop=yes). FPS overrides the header's fps (holds still win). Ctrl-C restores the cursor
# and returns 130; the caller's INT trap is put back.
huginn_play() {
  local src=${1:-} loops=${2:-0} fps_arg=${3:-} line kv i n=0 passes=0 rows=0 cols=0 fps=8
  local loop=no rest=first holds= pad=$'\xe2\xa0\x80' first=1 width ms up hp_fd hp_stop= oldtrap
  local -a fr=() cur=() hd=() dl=()
  [ -n "$src" ] || { echo 'huginn_play: usage: huginn_play FRAMES.txt|- [LOOPS] [FPS]' >&2; return 2; }
  [ "$src" = - ] && src=/dev/stdin
  while IFS= read -r line || [ -n "$line" ]; do
    if [ -n "$first" ]; then
      first=
      case $line in
        '# huginn-frames'*)
          for kv in ${line#\# huginn-frames}; do
            case $kv in
              cols=*) cols=${kv#*=} ;; rows=*) rows=${kv#*=} ;; fps=*) fps=${kv#*=} ;;
              loop=*) loop=${kv#*=} ;; rest=*) rest=${kv#*=} ;; holds=*) holds=${kv#*=} ;;
              pad=*) pad=${kv#*=} ;;
            esac
          done
          continue ;;
      esac
    fi
    [ -z "$line" ] && continue
    if [ "$line" = --- ]; then fr+=("$(printf '%s\n' "${cur[@]}")"); cur=(); continue; fi
    cur+=("${line//"$pad"/ }")
  done < "$src"
  [ ${#cur[@]} -gt 0 ] && fr+=("$(printf '%s\n' "${cur[@]}")")
  [ ${#fr[@]} -gt 0 ] || { echo "huginn_play: no frames in $1" >&2; return 2; }
  [ -n "$fps_arg" ] && fps=$fps_arg
  case $fps in ''|*[!0-9]*|0) fps=8 ;; esac
  if [ "$rows" -le 0 ] 2>/dev/null; then rows=1; line=${fr[0]}; line=${line//[!$'\n']/}; rows=$(( ${#line} + 1 )); fi
  # rest frame index: first | last | none | N
  case $rest in first) rest=0 ;; last) rest=$(( ${#fr[@]} - 1 )) ;; none) rest=-1 ;; *[!0-9]*) rest=0 ;; esac
  _huginn_play_rest() {   # print the rest frame with trailing blanks trimmed, no escape codes
    [ "$rest" -ge 0 ] || return 0
    while IFS= read -r line; do line=${line%"${line##*[! ]}"}; printf '%s\n' "$line"; done <<< "${fr[$rest]}"
  }
  if [ ! -t 1 ] || [ "${TERM:-dumb}" = dumb ] || [ -n "${HUGINN_NO_ANIM:-}" ]; then _huginn_play_rest; return 0; fi
  width=${COLUMNS:-}
  [ -n "$width" ] || width=$(stty size 2>/dev/null </dev/tty); width=${width##* }
  case $width in ''|*[!0-9]*) width=80 ;; esac
  if [ "$cols" -gt 0 ] && [ "$width" -lt "$cols" ]; then _huginn_play_rest; return 0; fi
  # per-frame delays as "s.mmm" strings for read -t, computed once (printf -v is a builtin: no forks)
  [ -n "$holds" ] && IFS=, read -r -a hd <<< "$holds"
  for i in "${!fr[@]}"; do
    ms=${hd[$i]:-}; case $ms in ''|*[!0-9]*) ms=$(( 1000 / fps )) ;; esac; [ "$ms" -gt 0 ] || ms=1
    printf -v 'dl[i]' '%d.%03d' $(( ms / 1000 )) $(( ms % 1000 ))
  done
  printf -v up '\033[%dA' "$rows"
  exec {hp_fd}<> <(:)            # a never-written pipe: read -t on it is a sleep without a fork
  oldtrap=$(trap -p INT); trap 'hp_stop=1' INT
  printf '\033[?25l'
  while :; do
    for i in "${!fr[@]}"; do
      [ "$n" -gt 0 ] && printf '%s' "$up"
      printf '%s\n' "${fr[$i]}"; n=$(( n + 1 ))
      read -rt "${dl[$i]}" -u "$hp_fd" line || :
      [ -n "$hp_stop" ] && break 2
    done
    passes=$(( passes + 1 ))
    if [ "$loops" -gt 0 ]; then [ "$passes" -ge "$loops" ] && break
    elif [ "$loop" != yes ]; then break; fi
  done
  printf '\033[?25h'; exec {hp_fd}>&-
  eval "${oldtrap:-trap - INT}"
  [ -n "$hp_stop" ] && { printf '\n'; return 130; }
  return 0
}
if [ "${BASH_SOURCE[0]}" = "$0" ]; then huginn_play "$@"; fi
