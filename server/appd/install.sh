#!/usr/bin/env bash
# Install (or update) huginn-appd on this host and prove it came up.
#
#   sudo bash server/appd/install.sh
#
# server/setup.sh runs this for a new server; server/appd/deploy.sh runs it after
# its test gate. It is idempotent: a second run replaces the daemon's files, keeps
# the token, the certificate, the data directory and every drop-in already there,
# and restarts the service.
#
# What it does, in order:
#   1. copies the daemon to /opt/huginn-appd (a plain copy, so a half-pulled checkout
#      never serves a half-updated daemon)
#   2. mints the bearer token at /etc/huginn-appd/token if there is none
#   3. installs the systemd unit and the hardening drop-in, and writes site.conf
#      once (HUGINN_WORKDIR -> HUGINN_APPD_WORKDIR) when it does not exist yet
#   4. adds the daemon's Claude Code hooks to ~/.claude/settings.json, and the
#      few Claude Code settings Huginn relies on where they are absent
#      (install-hooks.js --claude-defaults)
#   5. enables and restarts the service, then checks /v1/ping over HTTP and HTTPS
#      on loopback
#
# Environment: HUGINN_WORKDIR (where chats run; default the service's HOME),
# HUGINN_APPD_NO_START=1 (install only; used by image builds),
# HUGINN_KEEP_CLAUDE_FEEDBACK=1 (leave Claude Code's feedback prompts on).
set -euo pipefail
SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST=/opt/huginn-appd
TOKEN_FILE=/etc/huginn-appd/token
UNIT=/etc/systemd/system/huginn-appd.service
DROPIN_DIR=/etc/systemd/system/huginn-appd.service.d

say() { echo "[appd] $*"; }
die() { echo "[appd] $*" >&2; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run as root:  sudo bash $0"
for c in node openssl curl systemctl; do
  command -v "$c" >/dev/null 2>&1 || die "missing '$c' - run server/setup.sh, which installs it"
done
node --check "$SRC/huginn-appd.js"

# 1. the daemon's files
install -d "$DEST/lib" "$DEST/hooks" "$DEST/mcp"
install -m 0644 "$SRC/huginn-appd.js" "$DEST/huginn-appd.js"
install -m 0644 "$SRC"/lib/*.js "$DEST/lib/"
# huginn's MCP server (send_file): chats get it by --mcp-config, sessions by
# install-hooks' user-scope registration. It requires ../lib/outbox.js.
install -m 0755 "$SRC"/mcp/*.js "$DEST/mcp/"
# 0755: a hook without its exec bit fails open, silently, like a missing one.
install -m 0755 "$SRC"/hooks/* "$DEST/hooks/"
install -m 0644 "$SRC/install-hooks.js" "$DEST/install-hooks.js"

# 2. the token. The daemon refuses to start without one and rejects anything
# shorter than 32 characters.
if [ ! -s "$TOKEN_FILE" ]; then
  install -d -m 0700 "$(dirname "$TOKEN_FILE")"
  (umask 077; openssl rand -hex 32 > "$TOKEN_FILE")
  chmod 600 "$TOKEN_FILE"
  say "minted the access token at $TOKEN_FILE"
fi

# 3. the unit and its drop-ins. site.conf is the operator's file: written once
# from the installer's answers, never overwritten.
install -m 0644 "$SRC/huginn-appd.service" "$UNIT"
install -d "$DROPIN_DIR"
install -m 0644 "$SRC/systemd.d/hardening.conf" "$DROPIN_DIR/hardening.conf"
# A guard for the host, not for this unit: a manual start of systemd-tmpfiles-setup
# sweeps /tmp and with it the tmux socket every session lives behind (see the file).
install -d /etc/systemd/system/systemd-tmpfiles-setup.service.d
install -m 0644 "$SRC/systemd.d/tmpfiles-refuse-manual.conf" \
  /etc/systemd/system/systemd-tmpfiles-setup.service.d/huginn-refuse-manual.conf
if [ ! -e "$DROPIN_DIR/site.conf" ]; then
  {
    echo "# This server's own settings for huginn-appd. Written once by install.sh;"
    echo "# edit freely - installs and updates never overwrite it."
    echo "# Every variable the daemon reads: server/appd/systemd.d/README.md"
    echo "[Service]"
    if [ -n "${HUGINN_WORKDIR:-}" ]; then
      echo "Environment=HUGINN_APPD_WORKDIR=$HUGINN_WORKDIR"
    else
      echo "#Environment=HUGINN_APPD_WORKDIR=/root/projects"
    fi
    echo "#Environment=HUGINN_APPD_BIND=0.0.0.0"
  } > "$DROPIN_DIR/site.conf"
  say "wrote $DROPIN_DIR/site.conf"
fi
systemctl daemon-reload

# The data directory exists, private, before the first start. (A drop-in that adds
# ProtectSystem=strict with a ReadWritePaths= list naming it would otherwise stop
# the unit at 226/NAMESPACE before the daemon runs at all.)
DATA_DIR="${HUGINN_APPD_DATA:-$(systemctl show huginn-appd -p Environment --value 2>/dev/null \
  | tr ' ' '\n' | sed -n 's/^HUGINN_APPD_DATA=//p' | tail -1)}"
install -d -m 0700 "${DATA_DIR:-/var/lib/huginn-appd}"

# 4. the Claude Code hooks, pointed at the deployed copies under /opt. The
# headroom gate must watch the sentinel directory the SERVICE will use, which a
# drop-in can move (HUGINN_HEADROOM_DIR / HUGINN_APPD_DATA): read it from systemd,
# not from this shell.
unit_env() {
  systemctl show huginn-appd -p Environment --value 2>/dev/null \
    | tr ' ' '\n' | sed -n "s/^$1=//p" | tail -1 || true
}
HEADROOM_DIR="${HUGINN_HEADROOM_DIR:-$(unit_env HUGINN_HEADROOM_DIR)}"
if [ -z "$HEADROOM_DIR" ]; then
  APPD_DATA="${HUGINN_APPD_DATA:-$(unit_env HUGINN_APPD_DATA)}"
  HEADROOM_DIR="${APPD_DATA:-/var/lib/huginn-appd}/headroom"
fi
# Idempotent by command; each refuses (exit 2, nothing written) on a settings file
# it cannot parse, and that refusal stops the install.
node "$DEST/install-hooks.js" --script "$DEST/hooks/huginn-headroom-gate" --headroom-dir "$HEADROOM_DIR"
node "$DEST/install-hooks.js" --script "$DEST/hooks/huginn-permission-hook"
node "$DEST/install-hooks.js" --script "$DEST/hooks/huginn-modelswitch-hook"
# The Claude Code settings the apps' fast-mode control relies on, and the
# feedback prompts that would otherwise read as a question in an unwatched
# pane — each only where the key is absent. setup.sh writes the same set from
# its jq merge (server/claude-settings.jq), so a daemon-only update agrees with
# a full install. HUGINN_KEEP_CLAUDE_FEEDBACK=1 keeps the feedback prompts.
node "$DEST/install-hooks.js" --claude-defaults
# huginn's MCP server for the host's Claude Code SESSIONS (chats get it by
# --mcp-config on every run): registered at user scope, re-registered on every
# install so a moved node or $DEST is picked up, plus the one allow rule its
# send_file tool needs. Best-effort: a host without the claude CLI on PATH, or
# one whose CLI refuses, still gets a working daemon - and says so.
node "$DEST/install-hooks.js" --allow-send-file
if command -v claude >/dev/null 2>&1; then
  claude mcp remove -s user huginn >/dev/null 2>&1 || true
  # The NAME before -e: `--env` takes any number of values, so a name after it is
  # read as one more KEY=value and refused ("Invalid environment variable format:
  # huginn") - which is how 3.16.0 shipped, registering nothing.
  if claude mcp add -s user huginn -e "HUGINN_APPD_DATA=${DATA_DIR:-/var/lib/huginn-appd}" \
       -- "$(command -v node)" "$DEST/mcp/huginn-mcp.js" >/dev/null 2>&1; then
    say "registered huginn's MCP server (send_file) for Claude Code sessions"
  else
    say "WARNING: could not register huginn's MCP server with claude; sessions will not have send_file (chats still do)"
  fi
else
  say "no claude CLI on PATH: sessions will not have send_file (chats still do)"
fi

if [ "${HUGINN_APPD_NO_START:-}" = 1 ]; then
  say "installed; not started (HUGINN_APPD_NO_START=1)"
  exit 0
fi

# 5. start it and prove it. Polled, not slept: the first start mints the TLS
# certificate and restores sessions before it listens.
systemctl enable --quiet huginn-appd
systemctl restart huginn-appd
TOKEN="$(cat "$TOKEN_FILE")"
PORT="${HUGINN_APPD_PORT:-$(unit_env HUGINN_APPD_PORT)}"; PORT="${PORT:-8787}"
TLS_CERT="${HUGINN_APPD_TLS_CERT:-$(unit_env HUGINN_APPD_TLS_CERT)}"
if [ -z "$TLS_CERT" ]; then
  TLS_DATA="${HUGINN_APPD_DATA:-$(unit_env HUGINN_APPD_DATA)}"
  TLS_CERT="${TLS_DATA:-/var/lib/huginn-appd}/tls/cert.pem"
fi
# ⚠ THE TOKEN GOES ONLY TO THE DAEMON THAT HOLDS THIS CERTIFICATE'S KEY. Right
# after the restart above the port is briefly free, and whatever binds it first
# would be handed the token by the loop below over plain HTTP. So the checks run
# over HTTPS with curl's --pinnedpubkey, derived from appd's own certificate,
# which refuses any other key before a byte of the request is sent (-k only
# skips the name check on the self-signed certificate). The pin is derived on
# every try: the first start mints the certificate, and a renewal replaces it.
# ⚠ AND NEVER ON A COMMAND LINE. /proc/<pid>/cmdline is readable by every account
# on this host, the token is root-equivalent and never rotates, and the loop runs
# up to sixty curls on every install and update. The header reaches curl
# through a file descriptor (-H @file, curl 7.55+), and printf is a bash builtin.
# scripts/test-no-token-argv.sh and scripts/test-appd-pin.sh hold it to both.
appd_pin() {
  [ -s "$TLS_CERT" ] || return 1
  openssl x509 -in "$TLS_CERT" -pubkey -noout 2>/dev/null \
    | openssl pkey -pubin -outform der 2>/dev/null | openssl dgst -sha256 -binary | base64
}
appd_curl() {
  local pin; pin="$(appd_pin)" && [ -n "$pin" ] || return 1
  curl -k --pinnedpubkey "sha256//$pin" -H @<(printf 'Authorization: Bearer %s\n' "$TOKEN") "$@"
}
SPING= RC=
for _ in $(seq 1 60); do
  RC=0; SPING="$(appd_curl -sf -m 2 "https://127.0.0.1:$PORT/v1/ping")" || RC=$?
  [ -n "$SPING" ] && break
  sleep 0.5
done
grep -q '"ok":true' <<<"$SPING" || {
  journalctl -u huginn-appd -n 20 --no-pager >&2 || true
  [ -s "$TLS_CERT" ] || die "huginn-appd wrote no TLS certificate at $TLS_CERT (journal above)"
  # curl's 90: a listener answered with a key other than the one in appd's certificate.
  [ "$RC" != 90 ] || die "what holds 127.0.0.1:$PORT is not huginn-appd: its key is not the one in $TLS_CERT, and nothing was sent to it. See what owns the port: ss -ltnp 'sport = :$PORT'"
  die "huginn-appd did not answer on https://127.0.0.1:$PORT (journal above). If HUGINN_APPD_BIND is set, it must include loopback - use 0.0.0.0."
}
grep -q '"secure":true' <<<"$SPING" || die "huginn-appd answered, but not over TLS"
# The plain listener on the same port, asked WITHOUT the token: any answer from
# appd (a 401 is the usual one) shows it is there; nothing secret goes over it.
HCODE="$(curl -s -o /dev/null -m 5 -w '%{http_code}' "http://127.0.0.1:$PORT/v1/ping" || true)"
case "$HCODE" in ''|000) die "huginn-appd answers over HTTPS, but its plain HTTP listener on 127.0.0.1:$PORT did not answer" ;; esac
PING="$SPING"
FP="$(openssl x509 -in "$TLS_CERT" -noout -fingerprint -sha256 2>/dev/null | cut -d= -f2 | tr -d : | tr 'A-F' 'a-f')"
say "running: $(sed -n 's/.*"version":"\([^"]*\)".*/huginn-appd \1/p' <<<"$PING") on port $PORT, https ok${FP:+ (certificate sha256 $FP)}"
