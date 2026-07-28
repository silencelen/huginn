#!/usr/bin/env bash
# Deploy/update huginn-appd on this host (huginn LXC 117). Idempotent.
#   - installs the daemon to /opt/huginn-appd/
#   - creates /etc/huginn-appd/token (0600) on first run
#   - installs + (re)starts the systemd unit
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

install -d -m 755 /opt/huginn-appd
install -d -m 700 /etc/huginn-appd /var/lib/huginn-appd

if [ ! -f /etc/huginn-appd/token ]; then
  umask 077
  openssl rand -hex 32 > /etc/huginn-appd/token
  echo "[deploy] generated new token at /etc/huginn-appd/token"
fi

install -m 644 "$DIR/huginn-appd.js" /opt/huginn-appd/huginn-appd.js
install -m 644 "$DIR/huginn-appd.service" /etc/systemd/system/huginn-appd.service

systemctl daemon-reload
systemctl enable --now huginn-appd
sleep 1
systemctl restart huginn-appd
sleep 1
systemctl is-active --quiet huginn-appd && echo "[deploy] huginn-appd active" || {
  echo "[deploy] FAILED — journalctl -u huginn-appd -n 20:" >&2
  journalctl -u huginn-appd -n 20 --no-pager >&2
  exit 1
}
echo "[deploy] token for the app: cat /etc/huginn-appd/token"
