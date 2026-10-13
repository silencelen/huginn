#!/usr/bin/env bash
# package-arch.sh — turn a jpackage/Compose Multiplatform "app-image" directory into a native
# Arch Linux package (<name>-<ver>-<rel>-x86_64.pkg.tar.zst), built on the Debian/Ubuntu host
# that already builds the .deb. Same bytes under /opt/<name>/ as the .deb ships, so every path
# the app assumes (bin/<name>, lib/<name>.png, lib/app/*.cfg) is identical on both families.
#
# CANONICAL COPY: netplan scripts/active/package-arch.sh. Each app repo carries a verbatim copy
# beside its own release script (huginn mobile/scripts/, andvari scripts/, bifrost scripts/,
# yggdrasil scripts/). `diff` against the canonical copy before editing either side; the
# `--self-test` below is the compatibility contract every copy must keep passing.
#
# WHY HAND-ROLLED, NOT makepkg: makepkg refuses to run as root (huginn builds as root), needs
# fakeroot + a PKGBUILD + a source array, and all it would do for a binary repack is write
# .PKGINFO/.MTREE and tar the tree — which is ~40 lines of bsdtar below, in makepkg's own
# recipe (same .PKGINFO keys, same .MTREE options, same member order, zstd). pacman reads
# .PKGINFO only; .MTREE feeds `pacman -Qkk`; .BUILDINFO is never read and is omitted.
#
# Usage:
#   package-arch.sh --app-image DIR --name PKG --version X.Y.Z [--release N] \
#       --desc "one line" --url URL [--license custom|GPL-3.0-or-later] [--license-file FILE] \
#       [--display-name "Huginn"] [--categories "Utility;"] [--desktop-line "Key=Value"]... \
#       [--depends "a b c"] [--optdepend "pkg: why"]... [--install FILE] \
#       [--extra-file SRC:DEST]... [--packager "Name <email>"] [--out DIR]
#   package-arch.sh --self-test        # builds a dummy package and verifies it end to end
#
# Output: <out>/<name>-<version>-<release>-x86_64.pkg.tar.zst (+ .sha256). Exit non-zero and
# say why on any gate; never leaves a half-written package behind.
#
# Requires: bsdtar (apt: libarchive-tools), zstd, gzip. `pacman -Qip` is used for the final
# read-back when a pacman binary exists (apt: pacman-package-manager) and skipped otherwise.
set -euo pipefail

die() { echo "package-arch: ERROR: $*" >&2; exit 1; }
note() { echo "package-arch: $*" >&2; }

APP_IMAGE="" NAME="" VERSION="" RELEASE=1 DESC="" URL="" LICENSE="custom" LICENSE_FILE=""
DISPLAY_NAME="" CATEGORIES="Utility;" INSTALL_FILE="" OUT="" PACKAGER="${PACKAGER:-}"
# The runtime set a Compose/jpackage app-image links against on Arch: the deb's Depends line,
# translated (libasound2 -> alsa-lib, libfreetype6 -> freetype2, libgl1/libglvnd0 -> libglvnd,
# libxtst6 -> libxtst, ...). glibc/gcc-libs/zlib are base deps on every Arch install.
DEPENDS="alsa-lib fontconfig freetype2 libglvnd libx11 libxext libxi libxrender libxtst zlib hicolor-icon-theme"
OPTDEPENDS=() DESKTOP_LINES=() EXTRA_FILES=() SELF_TEST=0

while [ $# -gt 0 ]; do
  case "$1" in
    --app-image)    APP_IMAGE="${2:?}"; shift 2 ;;
    --name)         NAME="${2:?}"; shift 2 ;;
    --version)      VERSION="${2:?}"; shift 2 ;;
    --release)      RELEASE="${2:?}"; shift 2 ;;
    --desc)         DESC="${2:?}"; shift 2 ;;
    --url)          URL="${2:?}"; shift 2 ;;
    --license)      LICENSE="${2:?}"; shift 2 ;;
    --license-file) LICENSE_FILE="${2:?}"; shift 2 ;;
    --display-name) DISPLAY_NAME="${2:?}"; shift 2 ;;
    --categories)   CATEGORIES="${2:?}"; shift 2 ;;
    --desktop-line) DESKTOP_LINES+=("${2:?}"); shift 2 ;;
    --depends)      DEPENDS="${2:?}"; shift 2 ;;
    --optdepend)    OPTDEPENDS+=("${2:?}"); shift 2 ;;
    --install)      INSTALL_FILE="${2:?}"; shift 2 ;;
    --extra-file)   EXTRA_FILES+=("${2:?}"); shift 2 ;;
    --packager)     PACKAGER="${2:?}"; shift 2 ;;
    --out)          OUT="${2:?}"; shift 2 ;;
    --self-test)    SELF_TEST=1; shift ;;
    -h|--help)      sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown argument '$1' (see --help)" ;;
  esac
done

for t in bsdtar zstd gzip; do command -v "$t" >/dev/null 2>&1 || die "$t is missing (apt install libarchive-tools zstd)"; done

# ------------------------------------------------------------------ self-test
if [ "$SELF_TEST" = 1 ]; then
  T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
  mkdir -p "$T/img/bin" "$T/img/lib/app" "$T/img/lib/runtime/bin"
  printf '#!/bin/sh\necho hello from archtest\n' > "$T/img/bin/archtest"; chmod 755 "$T/img/bin/archtest"
  printf 'fake jar\n' > "$T/img/lib/app/archtest.jar"
  printf '\x89PNG fake\n' > "$T/img/lib/archtest.png"
  printf 'post_install() { echo "archtest installed"; }\npre_remove() { echo "archtest leaving"; }\n' > "$T/archtest.install"
  printf 'MIT text\n' > "$T/LICENSE"
  "$0" --app-image "$T/img" --name archtest --version 0.0.1 --desc "package-arch self-test" \
       --url https://example.invalid --license MIT --license-file "$T/LICENSE" --display-name "Arch Test" \
       --optdepend "nodejs: optional" --install "$T/archtest.install" \
       --desktop-line "MimeType=x-scheme-handler/archtest;" --packager "self-test <noreply@example.invalid>" \
       --out "$T/out" >&2
  PKG="$T/out/archtest-0.0.1-1-x86_64.pkg.tar.zst"
  [ -f "$PKG" ] || die "self-test: package not produced"
  LIST=$(bsdtar -tf "$PKG")
  for want in .PKGINFO .MTREE .INSTALL opt/archtest/bin/archtest usr/bin/archtest \
              usr/share/applications/archtest.desktop usr/share/licenses/archtest/LICENSE; do
    grep -qx "$want" <<<"$LIST" || die "self-test: $want missing from the package"
  done
  [ "$(head -3 <<<"$LIST" | tr '\n' ' ')" = ".PKGINFO .MTREE .INSTALL " ] || die "self-test: metadata members are not first: $(head -3 <<<"$LIST" | tr '\n' ' ')"
  bsdtar -xOf "$PKG" .PKGINFO | grep -q '^pkgver = 0.0.1-1$' || die "self-test: pkgver wrong"
  bsdtar -xOf "$PKG" .PKGINFO | grep -q '^optdepend = nodejs: optional$' || die "self-test: optdepend missing"
  bsdtar -xOf "$PKG" .MTREE | gzip -dc | grep -q '^\./opt/archtest/bin/archtest .*sha256digest=' || die "self-test: .MTREE lacks the launcher"
  bsdtar -xOf "$PKG" usr/share/applications/archtest.desktop | grep -q '^MimeType=x-scheme-handler/archtest;$' || die "self-test: desktop-line not applied"
  bsdtar -tvf "$PKG" usr/bin/archtest | grep -q -- '-> /opt/archtest/bin/archtest$' || die "self-test: /usr/bin symlink wrong"
  sha=$(cut -d' ' -f1 "$PKG.sha256"); [ "$sha" = "$(sha256sum "$PKG" | cut -d' ' -f1)" ] || die "self-test: .sha256 sidecar disagrees"
  note "self-test OK: $PKG ($(stat -c %s "$PKG") bytes)"
  exit 0
fi

# -------------------------------------------------------------------- gates
[ -n "$APP_IMAGE" ] && [ -n "$NAME" ] && [ -n "$VERSION" ] && [ -n "$DESC" ] && [ -n "$URL" ] \
  || die "--app-image, --name, --version, --desc and --url are required"
[ -d "$APP_IMAGE" ] || die "app-image '$APP_IMAGE' is not a directory"
[ -x "$APP_IMAGE/bin/$NAME" ] || die "app-image has no executable bin/$NAME — is this the createDistributable output for '$NAME'?"
[[ "$NAME" =~ ^[a-z0-9@._+-]+$ ]] || die "pkgname '$NAME' is not a valid Arch package name (lowercase [a-z0-9@._+-])"
[[ "$VERSION" =~ ^[0-9]+(\.[0-9]+)*$ ]] || die "version '$VERSION' must be dotted digits (pacman pkgver cannot carry '-' or ':')"
[[ "$RELEASE" =~ ^[0-9]+$ ]] || die "release '$RELEASE' must be an integer"
[ -z "$LICENSE_FILE" ] || [ -f "$LICENSE_FILE" ] || die "license file '$LICENSE_FILE' missing"
[ -z "$INSTALL_FILE" ] || [ -f "$INSTALL_FILE" ] || die "install script '$INSTALL_FILE' missing"
if [ -n "$INSTALL_FILE" ]; then
  sh -n "$INSTALL_FILE" || die "install script '$INSTALL_FILE' does not parse as sh"
  grep -qE '^(pre|post)_(install|upgrade|remove)\(\)' "$INSTALL_FILE" || die "install script defines no pre_/post_ install|upgrade|remove function"
fi
[ -n "$DISPLAY_NAME" ] || DISPLAY_NAME="$NAME"
[ -n "$PACKAGER" ] || PACKAGER="Unknown Packager"
[ -n "$OUT" ] || OUT="$PWD/dist"
# Absolute from here on: the packing step runs inside a subshell that has cd'd into the
# staging tree, where a relative --out or --app-image would point somewhere else entirely.
APP_IMAGE="$(cd "$APP_IMAGE" && pwd)"
mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"
[ -z "$INSTALL_FILE" ] || INSTALL_FILE="$(cd "$(dirname "$INSTALL_FILE")" && pwd)/$(basename "$INSTALL_FILE")"
[ -z "$LICENSE_FILE" ] || LICENSE_FILE="$(cd "$(dirname "$LICENSE_FILE")" && pwd)/$(basename "$LICENSE_FILE")"
PKGFILE="$NAME-$VERSION-$RELEASE-x86_64.pkg.tar.zst"

WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
PKGDIR="$WORK/pkg"; mkdir -p "$PKGDIR"

# ------------------------------------------------------------------ payload
# /opt/<name> = the app-image verbatim (the .deb's layout). cp -a keeps modes and the
# relative symlinks jpackage writes; ownership is normalised below because the package
# records uid/gid and a build user's ids mean nothing on the target.
install -d -m 755 "$PKGDIR/opt"
cp -a "$APP_IMAGE" "$PKGDIR/opt/$NAME"
install -d -m 755 "$PKGDIR/usr/bin" "$PKGDIR/usr/share/applications"
ln -s "/opt/$NAME/bin/$NAME" "$PKGDIR/usr/bin/$NAME"

ICON_LINE=""
[ -f "$APP_IMAGE/lib/$NAME.png" ] && ICON_LINE="Icon=/opt/$NAME/lib/$NAME.png"
{
  printf '[Desktop Entry]\n'
  printf 'Name=%s\n' "$DISPLAY_NAME"
  printf 'Comment=%s\n' "$DESC"
  printf 'Exec=/opt/%s/bin/%s\n' "$NAME" "$NAME"
  [ -n "$ICON_LINE" ] && printf '%s\n' "$ICON_LINE"
  printf 'Terminal=false\nType=Application\n'
  printf 'Categories=%s\n' "$CATEGORIES"
  for l in "${DESKTOP_LINES[@]+"${DESKTOP_LINES[@]}"}"; do printf '%s\n' "$l"; done
} > "$PKGDIR/usr/share/applications/$NAME.desktop"
chmod 644 "$PKGDIR/usr/share/applications/$NAME.desktop"

if [ -n "$LICENSE_FILE" ]; then
  install -D -m 644 "$LICENSE_FILE" "$PKGDIR/usr/share/licenses/$NAME/$(basename "$LICENSE_FILE")"
elif [ -f "$APP_IMAGE/share/doc/copyright" ]; then
  install -D -m 644 "$APP_IMAGE/share/doc/copyright" "$PKGDIR/usr/share/licenses/$NAME/copyright"
fi

for spec in "${EXTRA_FILES[@]+"${EXTRA_FILES[@]}"}"; do
  src="${spec%%:*}"; dest="${spec#*:}"
  [ "$src" != "$spec" ] && [ -n "$dest" ] || die "--extra-file wants SRC:DEST, got '$spec'"
  [ -f "$src" ] || die "--extra-file source '$src' missing"
  case "$dest" in /*) ;; *) die "--extra-file destination '$dest' must be absolute" ;; esac
  install -D -m "$( [ -x "$src" ] && echo 755 || echo 644 )" "$src" "$PKGDIR$dest"
done

chown -R 0:0 "$PKGDIR" 2>/dev/null || true   # non-root builders keep their uid; pacman copes

# ------------------------------------------------------------------ .PKGINFO
# makepkg's own key order. `size` is the installed size: apparent bytes, as makepkg
# computes it (du -sk --apparent-size * 1024). `xdata = pkgtype=pkg` is what pacman >= 6.1
# writes for a normal package; older pacman ignores unknown keys.
SIZE=$(( $(du -sk --apparent-size "$PKGDIR" | cut -f1) * 1024 ))
{
  printf '# Generated by package-arch.sh (bsdtar %s)\n' "$(bsdtar --version | awk '{print $2}')"
  printf 'pkgname = %s\npkgbase = %s\nxdata = pkgtype=pkg\npkgver = %s-%s\npkgdesc = %s\nurl = %s\n' \
    "$NAME" "$NAME" "$VERSION" "$RELEASE" "$DESC" "$URL"
  printf 'builddate = %s\npackager = %s\nsize = %s\narch = x86_64\nlicense = %s\n' \
    "${SOURCE_DATE_EPOCH:-$(date +%s)}" "$PACKAGER" "$SIZE" "$LICENSE"
  for d in $DEPENDS; do printf 'depend = %s\n' "$d"; done
  for o in "${OPTDEPENDS[@]+"${OPTDEPENDS[@]}"}"; do printf 'optdepend = %s\n' "$o"; done
} > "$PKGDIR/.PKGINFO"
chmod 644 "$PKGDIR/.PKGINFO"
if [ -n "$INSTALL_FILE" ]; then install -m 644 "$INSTALL_FILE" "$PKGDIR/.INSTALL"; fi

# ------------------------------------------------------------------- .MTREE
# makepkg's exact bsdtar options; member list sorted and relative (no leading ./), metadata
# files first — pacman reads .PKGINFO from the head of the stream without unpacking the rest.
(
  cd "$PKGDIR"
  find . -mindepth 1 ! -name .PKGINFO ! -name .INSTALL ! -name .MTREE -printf '%P\n' | LC_ALL=C sort > "$WORK/files"
  { printf '.PKGINFO\n'; [ -f .INSTALL ] && printf '.INSTALL\n'; cat "$WORK/files"; } > "$WORK/mtree-list"
  LANG=C bsdtar -czf .MTREE --format=mtree \
    --options='!all,use-set,type,uid,gid,mode,time,size,md5,sha256,link' \
    --files-from "$WORK/mtree-list"
  chmod 644 .MTREE
  { printf '.PKGINFO\n.MTREE\n'; [ -f .INSTALL ] && printf '.INSTALL\n'; cat "$WORK/files"; } > "$WORK/pkg-list"
  install -d -m 755 "$OUT"
  LANG=C bsdtar --no-fflags -cnf - --files-from "$WORK/pkg-list" | zstd -c -z -q -T0 - > "$OUT/$PKGFILE.tmp"
)
mv -f "$OUT/$PKGFILE.tmp" "$OUT/$PKGFILE"
( cd "$OUT" && sha256sum "$PKGFILE" > "$PKGFILE.sha256" )

# ----------------------------------------------------------------- read-back
# ARTIFACTS, never exit codes: prove the file is a package pacman would accept.
bsdtar -tf "$OUT/$PKGFILE" | head -1 | grep -qx '.PKGINFO' || die "$PKGFILE does not start with .PKGINFO"
bsdtar -xOf "$OUT/$PKGFILE" .PKGINFO | grep -qx "pkgver = $VERSION-$RELEASE" || die ".PKGINFO pkgver mismatch"
bsdtar -tf "$OUT/$PKGFILE" | grep -qx "opt/$NAME/bin/$NAME" || die "launcher missing from $PKGFILE"
if command -v pacman >/dev/null 2>&1; then
  # A private, empty db: the build host's /var/lib/pacman may not exist or be writable
  # (huginn's tmux namespace is read-only outside /root), and a file query needs no db anyway.
  mkdir -p "$WORK/pacdb" "$WORK/paccache" "$WORK/pacgpg"
  pacman -Qip --config /dev/null --dbpath "$WORK/pacdb" --cachedir "$WORK/paccache" --gpgdir "$WORK/pacgpg" \
    --logfile /dev/null "$OUT/$PKGFILE" > "$WORK/qip" 2>&1 || { cat "$WORK/qip" >&2; die "pacman -Qip rejected $PKGFILE"; }
  grep -q "^Version *: $VERSION-$RELEASE" "$WORK/qip" || { cat "$WORK/qip" >&2; die "pacman reads a different version out of $PKGFILE"; }
  note "pacman -Qip: $(grep -E '^(Name|Version|Installed Size)' "$WORK/qip" | tr -s ' ' | tr '\n' ';')"
fi
note "built $OUT/$PKGFILE ($(stat -c %s "$OUT/$PKGFILE") bytes, sha256 $(cut -d' ' -f1 "$OUT/$PKGFILE.sha256"))"
echo "$OUT/$PKGFILE"
