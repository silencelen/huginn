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
#   4. adds the daemon's Claude Code hooks to ~/.claude/settings.json
#   5. enables and restarts the service, then checks /v1/ping over HTTP and HTTPS
#      on loopback
#
# Environment: HUGINN_WORKDIR (where chats run; default the service's HOME),
# HUGINN_APPD_NO_START=1 (install only; used by image builds).
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
install -d "$DEST/lib" "$DEST/hooks"
install -m 0644 "$SRC/huginn-appd.js" "$DEST/huginn-appd.js"
install -m 0644 "$SRC"/lib/*.js "$DEST/lib/"
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
# ⚠ THE TOKEN NEVER GOES ON A COMMAND LINE. /proc/<pid>/cmdline is readable by
# every account on this host, the token is root-equivalent and never rotates,
# and the loop below runs up to sixty curls on every install and update. The
# header reaches curl through a file descriptor instead (-H @file, curl 7.55+),
# and printf is a bash builtin, so no process carries the token in its
# arguments. scripts/test-no-token-argv.sh holds every script to this.
appd_curl() { curl -H @<(printf 'Authorization: Bearer %s\n' "$TOKEN") "$@"; }
PING=
for _ in $(seq 1 60); do
  PING="$(appd_curl -sf -m 2 "http://127.0.0.1:$PORT/v1/ping" || true)"
  [ -n "$PING" ] && break
  sleep 0.5
done
grep -q '"ok":true' <<<"$PING" || {
  journalctl -u huginn-appd -n 20 --no-pager >&2 || true
  die "huginn-appd did not answer on 127.0.0.1:$PORT (journal above). If HUGINN_APPD_BIND is set, it must include loopback - use 0.0.0.0."
}
SPING="$(appd_curl -sfk -m 5 "https://127.0.0.1:$PORT/v1/ping" || true)"
grep -q '"secure":true' <<<"$SPING" || die "huginn-appd is up but its HTTPS listener did not answer"
TLS_CERT="${HUGINN_APPD_TLS_CERT:-$(unit_env HUGINN_APPD_TLS_CERT)}"
if [ -z "$TLS_CERT" ]; then
  TLS_DATA="${HUGINN_APPD_DATA:-$(unit_env HUGINN_APPD_DATA)}"
  TLS_CERT="${TLS_DATA:-/var/lib/huginn-appd}/tls/cert.pem"
fi
FP="$(openssl x509 -in "$TLS_CERT" -noout -fingerprint -sha256 2>/dev/null | cut -d= -f2 | tr -d : | tr 'A-F' 'a-f')"
say "running: $(sed -n 's/.*"version":"\([^"]*\)".*/huginn-appd \1/p' <<<"$PING") on port $PORT, https ok${FP:+ (certificate sha256 $FP)}"
