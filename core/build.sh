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
LDFLAGS="-s -w -buildid= -X github.com/silencelen/huginn/core/internal/version.Version=$V"

# key           GOOS    GOARCH  file
TARGETS=(
  "linux-x64     linux   amd64   huginn-$V-linux-x64"
  "linux-arm64   linux   arm64   huginn-$V-linux-arm64"
  "windows-x64   windows amd64   huginn-$V-windows-x64.exe"
  "android-arm64 android arm64   huginn-$V-android-arm64"
)
cd "$HERE"
for t in "${TARGETS[@]}"; do
  read -r key goos goarch file <<< "$t"
  CGO_ENABLED=0 GOOS=$goos GOARCH=$goarch go build -trimpath -ldflags "$LDFLAGS" -o "$OUT/$file" ./cmd/huginn
done

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
