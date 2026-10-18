#!/bin/sh
# Huginn Server, one line (Debian or Ubuntu, as root):
#
#   curl -fsSL https://raw.githubusercontent.com/silencelen/huginn/main/server/get.sh | sudo bash
#
# Downloads the newest Huginn release (never a moving branch) to
# /usr/local/src/huginn and runs its server/setup.sh. Running it again updates
# the server to the newest release. setup.sh's own variables pass through:
#
#   curl -fsSL .../server/get.sh | sudo HUGINN_WORKDIR=/root/projects bash
#
# HUGINN_REF=<tag or branch> installs that instead of the newest release.
set -eu
REPO=silencelen/huginn
DEST=/usr/local/src/huginn

say() { printf '[huginn] %s\n' "$*" >&2; }
die() { say "$*"; exit 1; }

[ "$(id -u)" -eq 0 ] || die "run as root:  curl -fsSL https://raw.githubusercontent.com/$REPO/main/server/get.sh | sudo bash"
[ -r /etc/debian_version ] || die "Huginn Server needs Debian or Ubuntu (no /etc/debian_version here)"
command -v curl >/dev/null 2>&1 || { apt-get update -q && apt-get install -y -q curl ca-certificates; }
command -v tar >/dev/null 2>&1 || apt-get install -y -q tar

REF="${HUGINN_REF:-}"
if [ -z "$REF" ]; then
  # Releases come back newest first. A server is the host side of the CLI and the
  # daemon, so the newest cli-v or appd-v release is the newest server.
  REF="$(curl -fsSL "https://api.github.com/repos/$REPO/releases?per_page=40" | tr -d '\n' \
    | grep -o '"tag_name"[[:space:]]*:[[:space:]]*"\(cli\|appd\)-v[0-9][0-9.]*"' | head -1 \
    | sed 's/.*"\([^"]*\)"$/\1/')" || true
  [ -n "$REF" ] || die "could not read the newest release from GitHub (rate-limited or offline?); set HUGINN_REF=<tag> to choose one"
fi

say "downloading $REPO $REF ..."
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl -fsSL "https://codeload.github.com/$REPO/tar.gz/$REF" -o "$TMP/huginn.tar.gz" \
  || die "download failed: https://codeload.github.com/$REPO/tar.gz/$REF"
mkdir "$TMP/src"
tar -xzf "$TMP/huginn.tar.gz" -C "$TMP/src" --strip-components=1
[ -f "$TMP/src/server/setup.sh" ] || die "$REF has no server/setup.sh"

rm -rf "$DEST.new"
mv "$TMP/src" "$DEST.new"
rm -rf "$DEST"
mv "$DEST.new" "$DEST"
printf '%s\n' "$REF" > "$DEST/.huginn-ref"
say "source for $REF is in $DEST"

exec bash "$DEST/server/setup.sh"
