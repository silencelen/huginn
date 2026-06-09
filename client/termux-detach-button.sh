#!/bin/bash
# Optional: add a one-tap "DTACH" button to Termux's extra-keys row (sends Ctrl-b d = tmux detach).
# Handy on a phone where Ctrl-b d is a 3-tap fumble. Run this on the PHONE (in Termux):
#     bash termux-detach-button.sh
mkdir -p ~/.termux
F="$HOME/.termux/termux.properties"
touch "$F"
cp "$F" "$F.bak-huginn" 2>/dev/null
# drop any existing extra-keys line, then add ours (DTACH macro + the usual keys)
grep -v '^[[:space:]]*extra-keys' "$F" > "$F.tmp" && mv "$F.tmp" "$F"
echo 'extra-keys = [["ESC","TAB","CTRL","ALT","-","/","|"],[{macro:"CTRL b d",display:"DTACH"},"HOME","UP","END","LEFT","DOWN","RIGHT"]]' >> "$F"
if command -v termux-reload-settings >/dev/null 2>&1; then
  termux-reload-settings
  echo "Done - a DTACH button is now in your Termux key row (one tap detaches)."
else
  echo "Added. Restart Termux (or run termux-reload-settings) to see the DTACH button."
fi
echo "(previous termux.properties backed up to $F.bak-huginn)"
