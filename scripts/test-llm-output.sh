#!/usr/bin/env bash
# test-llm-output.sh — huginn-llm hands the terminal text, never control sequences.
#
# WHY THIS EXISTS: huginn-llm prints a local model's answer to the operator's
# terminal (over ssh, for `huginn llm`), and a model writes what it read: asked
# to summarise a log that carries ESC [ 2K, CR or an OSC 52, it can echo them
# back, and the terminal then erases or rewrites lines or sets the clipboard
# (CWE-150). Every sibling renderer strips C0/C1 controls; huginn-llm now does
# too, keeping newline and tab because an answer is prose and code. This feeds
# the real script those sequences, through a stand-in curl, and reads what
# comes out - the answer on stdout and the daemon's refusals on stderr.
#
# Usage: scripts/test-llm-output.sh [tree]
#   tree defaults to this checkout; an export of an older commit shows the gate
#   failing there.
set -uo pipefail
TREE="$(cd "${1:-$(dirname "${BASH_SOURCE[0]}")/..}" && pwd)" || exit 1

FAIL=0
ok()   { echo "  ok    $*"; }
bad()  { echo "  FAIL  $*" >&2; FAIL=1; }

T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
od -An -tx1 -N24 /dev/urandom | tr -d ' \n' > "$T/token"; echo >> "$T/token"
sed "s#^TOKEN_FILE=/etc/huginn-appd/token\$#TOKEN_FILE=$T/token#" "$TREE/server/bin/huginn-llm" > "$T/huginn-llm"
grep -q "^TOKEN_FILE=$T/token\$" "$T/huginn-llm" || { bad "no TOKEN_FILE line to point at a scratch token (update this gate)"; exit 1; }

mkdir -p "$T/stub"
cat > "$T/stub/curl" <<'STUB'
#!/usr/bin/env bash
# Stands in for curl: answers huginn-llm's requests from $ROUTES (METHOD PATH BODY).
method="" url="" out="" wfmt=""
while [ $# -gt 0 ]; do
  case "$1" in
    -X) method="$2"; shift 2 ;;
    -H|-m|-d) shift 2 ;;
    -o) out="$2"; shift 2 ;;
    -w) wfmt="$2"; shift 2 ;;
    -*) shift ;;
    *) url="$1"; shift ;;
  esac
done
method="${method:-GET}"; path="/${url#*://*/}"; body='{"error":"no route"}'
while IFS=' ' read -r m p b; do
  if [ "$m" = "$method" ] && [ "$p" = "$path" ]; then body="$b"; break; fi
done < "$ROUTES"
if [ -n "$out" ]; then printf '%s' "$body" > "$out"; else printf '%s' "$body"; fi
[ -z "$wfmt" ] || printf '200'
STUB
chmod 755 "$T/stub/curl" "$T/huginn-llm"

# The sequences: an erase-line and a carriage return (rewrite a line), an OSC 52
# (set the clipboard) ended by BEL, the one-byte C1 CSI, DEL, a NUL, a backspace
# - with a newline and a tab among them, which must come through as they are.
ANSWER_JSON='"ok\u001b[2K\rALL CLEAR\u001b]52;c;cHduZWQ=\u0007 then \u009b31mred\u009b0m\nnext\tline\u007f\b end\u0000"'
cat > "$T/routes-answer" <<EOF
GET /v1/models?local=1 {"models":[{"id":"local:box:m","family":"local","available":true}]}
POST /v1/chats {"id":"c1"}
POST /v1/chats/c1/messages {"ok":true}
GET /v1/chats/c1 {"running":false,"messages":[{"type":"assistant","text":$ANSWER_JSON}]}
DELETE /v1/chats/c1 {"ok":true}
EOF
cat > "$T/routes-refused" <<'EOF'
GET /v1/models?local=1 {"models":[{"id":"local:box:m","family":"local","available":true}]}
POST /v1/chats {"error":"no machine is \u001b[32mSERVING\u001b[0m\r\u001b[2Kall fine"}
EOF
cat > "$T/routes-send" <<'EOF'
GET /v1/models?local=1 {"models":[{"id":"local:box:m","family":"local","available":true}]}
POST /v1/chats {"id":"c1"}
POST /v1/chats/c1/messages {"ok":false,"error":"busy\u001b]52;c;eA==\u0007"}
POST /v1/chats/c1/cancel {"ok":true}
DELETE /v1/chats/c1 {"ok":true}
EOF

# What may reach a terminal: no C0 control but newline and tab, no DEL, no C1.
controls() {  # $1 = file -> the offending bytes, as hex, if any
  python3 - "$1" <<'PY'
import sys
t = open(sys.argv[1], "rb").read().decode("utf-8", "replace")
bad = sorted({"%02x" % ord(c) for c in t if (ord(c) < 32 and c not in "\n\t") or 127 <= ord(c) < 160})
print(" ".join(bad))
PY
}

echo "[1/2] the answer: text through, control sequences not"
PATH="$T/stub:$PATH" ROUTES="$T/routes-answer" "$T/huginn-llm" "summarise this log" > "$T/out" 2> "$T/err"; RC=$?
# The expected text, from the rule stated independently: every C0 control but
# newline and tab, DEL, and every C1 control becomes a space.
python3 - "$ANSWER_JSON" > "$T/want" <<'PY'
import json, sys
t = json.loads(sys.argv[1])
print("".join(" " if (ord(c) < 32 and c not in "\n\t") or 127 <= ord(c) < 160 else c for c in t).rstrip("\n"))
PY
B="$(controls "$T/out")"
if [ "$RC" != 0 ]; then
  bad "huginn-llm exited $RC: $(head -c 300 "$T/err")"
elif [ -n "$B" ]; then
  bad "the answer reached the terminal with control bytes in it: $B"
elif ! cmp -s "$T/want" "$T/out"; then
  bad "the answer is not the text with its controls blanked: $(od -c "$T/out" | head -5)"
else
  ok "every control became a space; the newline, the tab and the words came through"
fi

echo "[2/2] the daemon's refusals, on stderr, get the same rule"
PATH="$T/stub:$PATH" ROUTES="$T/routes-refused" "$T/huginn-llm" "q" > "$T/out" 2> "$T/err"; RC=$?
B="$(controls "$T/err")"
if [ "$RC" != 1 ] || ! grep -q 'refused - no machine is' "$T/err"; then
  bad "a refused chat (exit $RC) did not say so: $(head -c 300 "$T/err")"
elif [ -n "$B" ]; then
  bad "the refusal reached the terminal with control bytes in it: $B"
else
  ok "a refused chat says why, without the sequences in it"
fi
PATH="$T/stub:$PATH" ROUTES="$T/routes-send" "$T/huginn-llm" "q" > "$T/out" 2> "$T/err"; RC=$?
B="$(controls "$T/err")"
if [ "$RC" != 1 ] || ! grep -q 'send refused - busy' "$T/err"; then
  bad "a refused message (exit $RC) did not say so: $(head -c 300 "$T/err")"
elif [ -n "$B" ]; then
  bad "the send refusal reached the terminal with control bytes in it: $B"
else
  ok "a refused message says why, without the sequences in it"
fi

echo
[ "$FAIL" = 0 ] && echo "llm-output gates: PASS" || { echo "llm-output gates: FAIL" >&2; exit 1; }
