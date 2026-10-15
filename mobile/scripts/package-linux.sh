#!/usr/bin/env bash
# The two non-Debian Linux artifacts, built from the SAME app-image the .deb wraps:
#
#   huginn-desktop-kt-<ver>-linux-x64.tar.gz      manifest key linux-x64-portable
#       the per-user, self-updating install (`huginn desktop install`). ONE
#       top-level dir, huginn-desktop-kt-<ver>/, so the client's listing check
#       (PortableInstall.checkTar / _huginn_desktop_tar_ok) can refuse anything else.
#   huginn-desktop-kt-<ver>-1-x86_64.pkg.tar.zst  manifest key linux-x64-arch
#       a native pacman package, so Arch users never debtap the .deb again.
#
# Called by release-desktop.sh step [2b/7]; runnable on its own so the artifacts
# can be built and checked WITHOUT touching the live channel or GitHub:
#
#   scripts/package-linux.sh <app-image-dir> <built.deb> <version> <outdir> [--no-launch]
#
# ⚠ NO makepkg HERE. huginn is Debian; the package is assembled the way makepkg
# assembles one — .PKGINFO, a gzip'd mtree .MTREE with makepkg's exact keyword
# set, then bsdtar | zstd over a C-sorted file list — and then READ BACK, because
# a packaging step that exits 0 having produced nonsense is the house failure
# shape (see release-desktop.sh's own comments on packageMsi).
#
# ⚠ THE ARCH PAYLOAD IS THE .deb's PAYLOAD (/opt/huginn-desktop-kt/…), plus the
# three files debtap added on the owner's laptop (/usr/bin link, menu entry,
# license). Same package NAME and same paths as that hand-converted install, so a
# `pacman -U` over it is an ordinary upgrade with no file conflicts.
set -euo pipefail

APP=${1:?app-image dir}; DEB=${2:?built .deb}; VERSION=${3:?version}; OUT=${4:?outdir}
LAUNCH=1
[ "${5:-}" = "--no-launch" ] && LAUNCH=0
echo "$VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$' || { echo "REFUSING: version '$VERSION' is not x.y.z" >&2; exit 1; }
[ -x "$APP/bin/huginn-desktop-kt" ] || { echo "REFUSING: $APP/bin/huginn-desktop-kt is not an app-image launcher" >&2; exit 1; }
[ -f "$DEB" ] || { echo "REFUSING: no deb at $DEB" >&2; exit 1; }
for t in tar gzip bsdtar zstd dpkg-deb; do
  command -v "$t" >/dev/null || { echo "REFUSING: $t is not installed (bsdtar: apt install libarchive-tools)" >&2; exit 1; }
done
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
# One timestamp for every file in both artifacts: SOURCE_DATE_EPOCH when the
# caller pins it, else the commit being released. Same tree in, same bytes out.
EPOCH=${SOURCE_DATE_EPOCH:-$(git -C "$REPO_ROOT" log -1 --format=%ct 2>/dev/null || echo 0)}
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ------------------------------------------------------------ portable tarball
TOP="huginn-desktop-kt-$VERSION"
TGZ="$OUT/$TOP-linux-x64.tar.gz"
# --transform renames the app-image dir to the versioned top WITHOUT a 175 MB
# copy. `flags=r` scopes it to member names: a symlink target that happened to
# start with the same word must never be rewritten.
tar --sort=name --owner=0 --group=0 --numeric-owner --mtime="@$EPOCH" --format=gnu \
    --transform="flags=r;s,^huginn-desktop-kt,$TOP," \
    -C "$(dirname "$APP")" -cf - "$(basename "$APP")" | gzip -n -9 > "$TGZ.tmp"
mv -f "$TGZ.tmp" "$TGZ"

# READ BACK: exactly one top dir, no absolute or `..` names — the same rules the
# clients enforce, so a release can never ship a tarball its own installer refuses.
TOPS="$(tar -tzf "$TGZ" | cut -d/ -f1 | sort -u)"
[ "$TOPS" = "$TOP" ] || { echo "REFUSING: $TGZ has top-level entries: $(echo $TOPS)" >&2; exit 1; }
if tar -tzf "$TGZ" | grep -qE '^/|(^|/)\.\.(/|$)'; then
  echo "REFUSING: $TGZ carries an absolute or '..' path" >&2; exit 1
fi
mkdir "$WORK/x"
tar -xzf "$TGZ" -C "$WORK/x"
L="$WORK/x/$TOP/bin/huginn-desktop-kt"
[ -x "$L" ] || { echo "REFUSING: the tarball's launcher is missing or not executable" >&2; exit 1; }
file "$L" | grep -q 'ELF 64-bit' || { echo "REFUSING: $L is not an x86-64 ELF launcher" >&2; exit 1; }
# The version the LAUNCHER will hand the JVM, not the one in the file name.
grep -qx "java-options=-Djpackage.app-version=$VERSION" "$WORK/x/$TOP/lib/app/huginn-desktop-kt.cfg" || {
  echo "REFUSING: the tarball's launcher config is not version $VERSION" >&2; exit 1; }
[ -f "$WORK/x/$TOP/lib/huginn-desktop-kt.png" ] || { echo "REFUSING: no icon at lib/huginn-desktop-kt.png" >&2; exit 1; }
echo "  $(basename "$TGZ") $(stat -c %s "$TGZ") bytes (one top dir, launcher $VERSION)"

# A RUN, the way the wine smoke proves the Windows build: a settings file the app
# wrote itself means its own code ran — a JVM that cannot find its main class is
# "alive" too. Throwaway HOME, so nothing lands in a real profile and no real
# instance's single-instance record can swallow the launch.
if [ "$LAUNCH" = 1 ]; then
  if ! command -v xvfb-run >/dev/null; then
    echo "REFUSING: xvfb-run is not installed (or pass --no-launch)" >&2; exit 1
  fi
  PH="$WORK/home"; mkdir -p "$PH"
  # ⚠ -Duser.home TOO: the JVM takes user.home from the passwd entry, NOT from
  # $HOME, so without it anything that falls back to user.home (the scheme
  # registrar does, when XDG_DATA_HOME is unset) writes into the BUILD USER's real
  # profile — found doing exactly that to /root during this script's first run.
  ( cd "$WORK" && HOME="$PH" XDG_CONFIG_HOME="$PH/.config" XDG_DATA_HOME="$PH/.local/share" \
      XDG_CACHE_HOME="$PH/.cache" JAVA_TOOL_OPTIONS="-Duser.home=$PH" timeout 90 xvfb-run -a -s "-screen 0 1400x900x24" "$L" \
      > "$WORK/launch.log" 2>&1 & ) 2>/dev/null
  for _ in $(seq 1 45); do
    [ -f "$PH/.config/huginn-desktop-kt/settings.json" ] && break
    sleep 2
  done
  # Bracketed so the pattern cannot match this shell's own command line.
  pkill -f "[x]/$TOP/bin/huginn-desktop-kt" >/dev/null 2>&1 || true
  pkill -f "[x]/$TOP/lib/runtime" >/dev/null 2>&1 || true
  [ -f "$PH/.config/huginn-desktop-kt/settings.json" ] || {
    tail -30 "$WORK/launch.log"
    echo "REFUSING: the unpacked tarball never got as far as writing its settings" >&2; exit 1; }
  echo "  unpacked tarball launched and initialised under xvfb"
fi
rm -rf "$WORK/x"

# ------------------------------------------------------------- arch package
PKG="$OUT/huginn-desktop-kt-$VERSION-1-x86_64.pkg.tar.zst"
R="$WORK/pkg"
mkdir -p "$R"
dpkg-deb -x "$DEB" "$R"
[ -x "$R/opt/huginn-desktop-kt/bin/huginn-desktop-kt" ] || { echo "REFUSING: the deb payload has no /opt launcher" >&2; exit 1; }
install -d "$R/usr/bin" "$R/usr/share/applications" "$R/usr/share/licenses/huginn-desktop-kt"
ln -s /opt/huginn-desktop-kt/bin/huginn-desktop-kt "$R/usr/bin/huginn-desktop-kt"
# The menu entry the .deb leaves to its postinst (xdg-desktop-menu). pacman runs
# no postinst here and needs none: Arch's desktop-file-utils hook refreshes the
# MIME cache whenever /usr/share/applications changes. Unlike the .deb's entry
# this one claims huginn:// from the start — the per-user entry the app writes
# (SchemeRegistrar, same file ID huginn-desktop-kt.desktop) then overrides it.
cat > "$R/usr/share/applications/huginn-desktop-kt.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=Huginn
Comment=Huginn desktop client
Exec=/opt/huginn-desktop-kt/bin/huginn-desktop-kt %u
Icon=/opt/huginn-desktop-kt/lib/huginn-desktop-kt.png
Terminal=false
Categories=Development;Utility;
MimeType=x-scheme-handler/huginn;
DESKTOP
install -m 644 "$REPO_ROOT/LICENSE" "$R/usr/share/licenses/huginn-desktop-kt/LICENSE"
chmod -R u+rwX,go+rX,go-w "$R"

SIZE=$(( $(du -sk --apparent-size "$R" | cut -f1) * 1024 ))
# Depends: the .deb's list, translated, DIRECT dependencies only — the bundled
# runtime's own NEEDED sonames (ldd over the app-image: libasound, fontconfig,
# freetype, libgif, libjpeg.so.8, liblcms2, libpng16, harfbuzz, libGL, X11 + ext,
# zlib); glib2/brotli/expat/pcre2 come in through those. xdg-utils because the
# app shells out to xdg-open / xdg-mime. nodejs stays optional, as the .deb's
# Recommends.
{
  echo "# Generated by mobile/scripts/package-linux.sh (makepkg-compatible; no makepkg on the build host)"
  echo "pkgname = huginn-desktop-kt"
  echo "pkgbase = huginn-desktop-kt"
  echo "xdata = pkgtype=pkg"
  echo "pkgver = $VERSION-1"
  echo "pkgdesc = Huginn desktop client"
  echo "url = https://github.com/silencelen/huginn"
  echo "builddate = $EPOCH"
  echo "packager = silencelen <release-desktop.sh>"
  echo "size = $SIZE"
  echo "arch = x86_64"
  echo "license = MIT"
  for d in alsa-lib fontconfig freetype2 gcc-libs giflib glibc harfbuzz lcms2 libglvnd \
           libjpeg-turbo libpng libx11 libxext libxi libxrender libxtst xdg-utils zlib; do
    echo "depend = $d"
  done
  echo "optdepend = nodejs: running work as a device and serving local AI"
} > "$R/.PKGINFO"

find "$R" -exec touch -h -d "@$EPOCH" {} +
# makepkg's list_package_files: every path, dotfiles included, C-collated, no ./.
list_files() { (cd "$R" && find . -mindepth 1 -printf '%P\0' | LC_ALL=C sort -z); }
list_files | (cd "$R" && LANG=C bsdtar -cnf - --format=mtree --uid 0 --gid 0 --uname root --gname root \
    --options='!all,use-set,type,uid,gid,mode,time,size,md5,sha256,link' \
    --null --files-from - --exclude .MTREE) | gzip -c -f -n > "$R/.MTREE"
touch -h -d "@$EPOCH" "$R/.MTREE"
list_files | (cd "$R" && LANG=C bsdtar --no-fflags -cnf - --uid 0 --gid 0 --uname root --gname root \
    --null --files-from -) | zstd -c -z -q -T0 -19 - > "$PKG.tmp"
mv -f "$PKG.tmp" "$PKG"

# READ BACK, from the PACKAGE, not from the staging dir.
file "$PKG" | grep -q 'Zstandard compressed' || { echo "REFUSING: $PKG is not zstd" >&2; exit 1; }
LIST="$(bsdtar -tf "$PKG")"
for want in .PKGINFO .MTREE opt/huginn-desktop-kt/bin/huginn-desktop-kt usr/share/applications/huginn-desktop-kt.desktop; do
  grep -qx "$want" <<<"$LIST" || { echo "REFUSING: $PKG does not carry $want" >&2; exit 1; }
done
[ "$(head -1 <<<"$LIST")" = ".MTREE" ] || { echo "REFUSING: $PKG does not open with .MTREE (pacman reads it first)" >&2; exit 1; }
if bsdtar -tvf "$PKG" | awk '{print $3" "$4}' | grep -qv '^root root$'; then
  echo "REFUSING: $PKG has entries not owned by root:root" >&2; exit 1
fi
PI="$(bsdtar -xOf "$PKG" .PKGINFO)"
pi() { sed -n "s/^$1 = //p" <<<"$PI" | head -1; }
[ "$(pi pkgname)" = huginn-desktop-kt ] && [ "$(pi pkgver)" = "$VERSION-1" ] && [ "$(pi arch)" = x86_64 ] \
  && [ "$(pi size)" -gt 0 ] || { echo "REFUSING: $PKG's .PKGINFO does not read back:"; echo "$PI"; exit 1; } >&2
MT="$(bsdtar -xOf "$PKG" .MTREE | gzip -dc)"
grep -q '^#mtree' <<<"$MT" && grep -q 'sha256digest=' <<<"$MT" \
  && grep -q '^\./opt/huginn-desktop-kt/bin/huginn-desktop-kt ' <<<"$MT" \
  || { echo "REFUSING: $PKG's .MTREE is not a makepkg-shaped mtree" >&2; exit 1; }
echo "  $(basename "$PKG") $(stat -c %s "$PKG") bytes (pkgver $(pi pkgver), $(grep -c '^depend' <<<"$PI") depends)"
