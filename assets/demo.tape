# Huginn demo GIF, rendered with VHS (https://github.com/charmbracelet/vhs):
#
#     vhs assets/demo.tape          writes assets/screenshots/demo.gif
#
# Needs a machine with the huginn command paired to a server that has a few sessions running,
# and IBM Plex Mono installed. It drives real commands against that server, so the GIF shows
# whatever sessions are there.

Output assets/screenshots/demo.gif

Set Shell bash
Set FontFamily "IBM Plex Mono"
Set FontSize 17
Set Width 1180
Set Height 660
Set Padding 22
Set Framerate 24
Set TypingSpeed 55ms
Set Theme {"name": "Huginn Moss & Heather", "background": "#141615", "foreground": "#E2E6E1", "selection": "#2C4035", "cursor": "#7CB98E", "black": "#3A423E", "red": "#D65A62", "green": "#7CB98E", "yellow": "#D4B872", "blue": "#6C9BD6", "magenta": "#A98BD6", "cyan": "#5EB5B0", "white": "#C8CDC9", "brightBlack": "#747D77", "brightRed": "#EE757D", "brightGreen": "#C4CF7A", "brightYellow": "#E8D07E", "brightBlue": "#86B5E6", "brightMagenta": "#B49CDB", "brightCyan": "#80CFC9", "brightWhite": "#E2E6E1"}

# Without a UTF-8 locale the console draws its 7-bit fallback art.
Hide
Type "export PS1='$ ' COLORTERM=truecolor LANG=C.UTF-8; clear" Enter
Show

# The sessions on the server, from any machine.
Type "huginn ls" Sleep 300ms Enter Sleep 2.5s

# The console: what needs you is at the top.
Type "huginn" Sleep 300ms Enter Sleep 3.5s

# Sessions, then open the one that is waiting.
Type "1" Sleep 2.5s
Enter Sleep 4s

# Detach: the session keeps running on the server.
Alt+d Sleep 2.5s

Type "q" Sleep 1s
