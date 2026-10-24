# Huginn's edits to the sessions account's ~/.claude/settings.json, as a jq
# program: server/setup.sh runs it with $mode "install", server/uninstall.sh
# with $mode "uninstall". Input is the whole settings object; output is the
# whole object with only these keys changed. (server/appd/install-hooks.js
# --claude-defaults writes the same defaults from the daemon's own deploy path.)
#
#   $bin            the installed huginn-statusline (/usr/local/bin/huginn-statusline)
#   $keep_feedback  "1" leaves the three feedback keys alone (HUGINN_KEEP_CLAUDE_FEEDBACK=1)
#
# The status line. Claude Code runs `statusLine.command` through a shell with
# the status JSON on stdin. A status line that was configured before Huginn's
# is kept as the argument after `--`, shell-quoted, and huginn-statusline runs
# it and prints its output after recording the JSON — so nothing is lost, and
# settings.json gains no key Claude Code does not know. A command that is
# already Huginn's (a re-run of setup) is left exactly as it is: wrapping it
# again would chain Huginn to itself. `padding` is kept.
#
# The defaults, each only where the key is ABSENT: an operator's own value,
# whatever it is, is never touched and nothing is ever removed.
#
#   fastModePerSessionOptIn: true     a session's /fast changes that session only
#   feedbackSurveyRate: 0, feedbackDrafts: "off", env.CLAUDE_CODE_DISABLE_FEEDBACK_SURVEY: "1"
#                                     no feedback prompts in a pane nobody is watching
#
# Uninstall puts the previous status line back (or removes `statusLine` when
# there was none) and leaves the four defaults in place, by design.

def shq: "'" + gsub("'"; "'\\''") + "'";
def unquote: if test("^'.*'$") then .[1:-1] | gsub("'\\\\''"; "'") else . end;
def trim: gsub("^\\s+|\\s+$"; "");
def ours: ((.statusLine.command // "") | tostring | test("(^|/)huginn-statusline(\\s|$)"));

def install_statusline:
  if ours then .
  else
    (((.statusLine.command // "") | tostring | trim)) as $prev
    | ((if (.statusLine | type) == "object" and (.statusLine.padding | type) == "number"
        then {padding: .statusLine.padding} else {} end)) as $pad
    | .statusLine = ({type: "command",
                      command: ($bin + (if $prev != "" then " -- " + ($prev | shq) else "" end))} + $pad)
  end;

def install_defaults:
  (if $keep_feedback == "1" then .
   else
     (if has("feedbackSurveyRate") then . else .feedbackSurveyRate = 0 end)
     | (if has("feedbackDrafts") then . else .feedbackDrafts = "off" end)
     | (if (.env | type) == "object"
        then (if (.env | has("CLAUDE_CODE_DISABLE_FEEDBACK_SURVEY")) then . else .env.CLAUDE_CODE_DISABLE_FEEDBACK_SURVEY = "1" end)
        elif has("env") then .      # an env that is not an object is somebody's to fix
        else .env = {CLAUDE_CODE_DISABLE_FEEDBACK_SURVEY: "1"} end)
   end)
  | (if has("fastModePerSessionOptIn") then . else .fastModePerSessionOptIn = true end);

def uninstall_statusline:
  if ours then
    ((.statusLine.command | tostring | capture("^\\S*huginn-statusline\\s+--\\s+(?<prev>.+)$")) // null) as $m
    | if $m then .statusLine.command = ($m.prev | trim | unquote) else del(.statusLine) end
  else . end;

if $mode == "uninstall" then uninstall_statusline
else install_statusline | install_defaults end
