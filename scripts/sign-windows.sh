#!/usr/bin/env bash
# Authenticode-sign Windows binaries (PE .exe, .msi) in place, on Linux.
#
#   scripts/sign-windows.sh <file>...
#
# WHY: Windows Defender's behaviour model removed the UNSIGNED huginn core as
# `Behavior:Win32/Persistence.A!ml` (2026-10-14): an unknown binary that copies
# itself into the profile and registers a boot task is what malware does. A
# signature chained to a root the machine trusts changes that verdict.
#
# The key and certificate live OUTSIDE the repo (this repo is public), in
# HUGINN_SIGN_DIR (default /root/.secrets/codesign): signing.key, signing.crt,
# ca.crt. Today that certificate is issued by jnet-ODIN-CA, so only machines in
# the jnet.ad domain trust it; a publicly trusted profile (Azure Artifact
# Signing) replaces the files, not this script.
#
# Without a key it prints UNSIGNED and leaves the files alone, unless
# HUGINN_SIGN_REQUIRED=1 (every release sets it), in which case it fails: an
# unsigned Windows artifact must never ship by accident.
set -euo pipefail
DIR="${HUGINN_SIGN_DIR:-/root/.secrets/codesign}"
KEY="$DIR/signing.key"; CRT="$DIR/signing.crt"; CA="$DIR/ca.crt"
# Two RFC 3161 servers: a timestamp keeps a signature valid after the
# certificate expires, and one server being down must not stop a release.
TSA=("${HUGINN_SIGN_TSA:-http://timestamp.digicert.com}" "http://timestamp.sectigo.com")
[ $# -gt 0 ] || { echo "usage: sign-windows.sh <file>..." >&2; exit 2; }
if [ ! -r "$KEY" ] || [ ! -r "$CRT" ]; then
  if [ "${HUGINN_SIGN_REQUIRED:-0}" = 1 ]; then echo "REFUSING: no signing key in $DIR and HUGINN_SIGN_REQUIRED=1" >&2; exit 1; fi
  for f in "$@"; do echo "UNSIGNED (no key in $DIR): $f" >&2; done; exit 0
fi
command -v osslsigncode >/dev/null || { echo "REFUSING: osslsigncode is not installed (apt install osslsigncode)" >&2; exit 1; }
for f in "$@"; do
  [ -f "$f" ] || { echo "REFUSING: $f does not exist" >&2; exit 1; }
  out="$f.signed.tmp"; rm -f "$out"; ok=""
  for ts in "${TSA[@]}"; do
    if osslsigncode sign -h sha256 -certs "$CRT" -ac "$CA" -key "$KEY" \
         -n "${HUGINN_SIGN_DESC:-huginn}" -i "${HUGINN_SIGN_URL:-https://github.com/silencelen/huginn}" \
         -ts "$ts" -in "$f" -out "$out" >/dev/null 2>&1; then ok=1; break; fi
    rm -f "$out"; echo "  timestamp server $ts failed, trying the next" >&2
  done
  [ -n "$ok" ] || { echo "REFUSING: could not sign $f (every timestamp server failed)" >&2; exit 1; }
  # Verify against the CA BEFORE the swap: a bad signature must never replace
  # a good file.
  osslsigncode verify -CAfile "$CA" -in "$out" >/dev/null 2>&1 || { rm -f "$out"; echo "REFUSING: the signature on $f does not verify" >&2; exit 1; }
  mv -f "$out" "$f"
  echo "signed: $f"
done
