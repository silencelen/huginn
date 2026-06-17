# Huginn demo — generate a GIF with VHS (https://github.com/charmbracelet/vhs):
#     vhs assets/demo.tape        ->  writes docs/demo.gif
#
# Prereq: the `huginn` command installed and a reachable host (edit nothing if your
# `Host huginn` SSH alias is set up; otherwise set HUGINN_HOST first).
# This drives real commands, so it shows your actual node. Tweak freely.
#
# Note: a real link drop (laptop sleep / Wi-Fi flap) can't be scripted here, so the
# auto-reconnect beat below is narrated. Live, the attach re-runs itself and drops
# you back into the same session within ~45s of waking.

Output docs/demo.gif

Set Shell bash
Set FontSize 20
Set Width 1100
Set Height 640
Set Padding 24
Set Theme "Catppuccin Mocha"
Set TypingSpeed 60ms

Hide
Type "clear" Enter
Show

Type "# one command, from any device" Enter Sleep 800ms
Type "huginn status" Sleep 400ms Enter Sleep 2.5s

Type "" Enter
Type "# a named session -> the terminal tab becomes 'huginn:costtracking'" Enter Sleep 800ms
Type "huginn list" Sleep 400ms Enter Sleep 2s

Type "" Enter
Type "# close the lid mid-task? it keeps running on the host." Enter Sleep 700ms
Type "# wake the laptop and huginn auto-reconnects you, right where you left off." Enter Sleep 1.8s

Type "" Enter
Type "# resume full-screen, detaching other devices" Enter Sleep 600ms
Type "huginn solo" Sleep 600ms Enter Sleep 2.5s

# (you're now attached to the live Claude Code tmux session)
Sleep 1.5s
