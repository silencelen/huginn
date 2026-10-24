#!/usr/bin/env bash
# Build the huginn core binary for every shipped platform, reproducibly, plus
# core-manifest.json (same shape as the desktop's manifest.json) for the
# release and the updater.
#
#   core/build.sh <version> [outdir]      default outdir: core/dist/<version>
#
# Reproducible: CGO off, -trimpath, empty build id — two builds of one commit
# give identical bytes, so a published sha256 can be re-derived by anyone.
set -euo pipefail
V="${1:?usage: core/build.sh <x.y.z> [outdir]}"
[[ "$V" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || { echo "REFUSING: version '$V' is not x.y.z" >&2; exit 2; }
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${2:-$HERE/dist/$V}"
mkdir -p "$OUT"
# ABSOLUTE before the cd below: a relative outdir (release-desktop.sh passed one)
# otherwise lands under core/ and the caller finds nothing (caught by the 2.0.0 cut).
OUT="$(cd "$OUT" && pwd)"
LDFLAGS="-s -w -buildid= -X github.com/silencelen/huginn/core/internal/version.Version=$V"

# key           GOOS    GOARCH  file
TARGETS=(
  "linux-x64     linux   amd64   huginn-$V-linux-x64"
  "linux-arm64   linux   arm64   huginn-$V-linux-arm64"
  "windows-x64   windows amd64   huginn-$V-windows-x64.exe"
  "android-arm64 android arm64   huginn-$V-android-arm64"
)
cd "$HERE"
# The core carries the CLI (internal/clientkit). Its copy must be the commit's
# client/, and a release must carry the CLI of its own version: refuse rather
# than copy, so the binary is always exactly what the commit says.
for f in huginn.sh huginn.ps1 huginn-local huginn-llm-shim; do
  cmp -s <(tr -d '\r' < "$HERE/../client/$f") "$HERE/internal/clientkit/files/$f.txt" || {
    echo "REFUSING: core/internal/clientkit/files/$f.txt differs from client/$f." >&2
    echo "  tr -d '\\r' < client/$f > core/internal/clientkit/files/$f.txt   and commit it" >&2
    exit 1
  }
done
if [[ "$V" != *-* ]]; then
  grep -qx "HUGINN_VERSION='$V'" internal/clientkit/files/huginn.sh.txt \
    || { echo "REFUSING: the CLI this core carries is not $V (client/huginn.sh HUGINN_VERSION)" >&2; exit 1; }
fi
# The core carries the Claude Code skills too (internal/skills): same rule.
for s in huginn huginn-doctor; do
  diff -rq "$HERE/../skills/$s" "$HERE/internal/skills/files/$s" >/dev/null || {
    echo "REFUSING: core/internal/skills/files/$s differs from skills/$s." >&2
    echo "  rm -r core/internal/skills/files/$s && cp -r skills/$s core/internal/skills/files/   and commit it" >&2
    exit 1
  }
done
for t in "${TARGETS[@]}"; do
  read -r key goos goarch file <<< "$t"
  CGO_ENABLED=0 GOOS=$goos GOARCH=$goarch go build -trimpath -ldflags "$LDFLAGS" -o "$OUT/$file" ./cmd/huginn
done

# Authenticode-sign the Windows binary BEFORE the manifest hashes it, so the
# published sha256 is the signed file's (scripts/sign-windows.sh says why).
# Signing adds a timestamp, so the signed .exe is not bit-reproducible; the
# unsigned build underneath still is. HUGINN_SIGN_REQUIRED=1 (releases) turns a
# missing key into a failure.
"$HERE/../scripts/sign-windows.sh" "$OUT/huginn-$V-windows-x64.exe"

# The manifest. Keys are platform words the installers and the updater pick by;
# never rename one without both.
python3 - "$OUT" "$V" "${TARGETS[@]}" <<'PY'
import hashlib, json, os, sys, datetime
out, v, targets = sys.argv[1], sys.argv[2], sys.argv[3:]
arts = {}
for t in targets:
    key, _goos, _arch, f = t.split()
    p = os.path.join(out, f)
    arts[key] = {"file": f, "size": os.path.getsize(p), "sha256": hashlib.sha256(open(p, "rb").read()).hexdigest()}
m = {"version": v, "proto": "1", "releasedAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "artifacts": arts}
open(os.path.join(out, "core-manifest.json"), "w").write(json.dumps(m, indent=2) + "\n")
PY
( cd "$OUT" && sha256sum huginn-* > SHA256SUMS )
ls -la "$OUT"
