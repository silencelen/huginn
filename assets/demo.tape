# Huginn demo — generate a GIF with VHS (https://github.com/charmbracelet/vhs):
#     vhs assets/demo.tape        ->  writes docs/demo.gif
#
# Prereq: the `huginn` command installed and a reachable host (edit nothing if your
# `Host huginn` SSH alias is set up; otherwise set HUGINN_HOST first).
# This drives real commands, so it shows your actual node. Tweak freely.

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
Type "# list your live sessions" Enter Sleep 600ms
Type "huginn list" Sleep 400ms Enter Sleep 2s

Type "" Enter
Type "# resume full-screen, detaching other devices" Enter Sleep 600ms
Type "huginn solo" Sleep 600ms Enter Sleep 2.5s

# (you're now attached to the live Claude Code tmux session)
Sleep 1.5s
