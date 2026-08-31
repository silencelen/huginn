#!/usr/bin/env bash
# Cut (or refresh) a GitHub Release for one huginn component.
#
#   scripts/github-release.sh <core|app|desktop|appd> <version> [artifact ...]
#
# One repo, four independently-versioned components, so tags are namespaced:
#   core    -> v<version>          (the CLI + server core; CHANGELOG.md)
#   app     -> app-v<version>      (Android;    mobile/CHANGELOG.md)
#   desktop -> desktop-v<version>  (Compose;    mobile/app-desktop/CHANGELOG.md)
#   appd    -> appd-v<version>     (the daemon; server/appd/CHANGELOG.md)
#
# Release notes are CUT FROM THE COMPONENT'S CHANGELOG (the section whose `## `
# heading carries the version), so the changelog stays the single place notes are
# written — a release with no changelog section is refused, same spirit as
# release-desktop.sh's gate. Override with HUGINN_RELEASE_NOTES_FILE=<path>.
#
# Idempotent: an existing tag is left where it is (a tag is history, not a
# pointer to move); an existing release gets its notes refreshed and artifacts
# re-uploaded with --clobber. A missing tag is created at HEAD (override with
# HUGINN_RELEASE_REF=<commit>) and pushed.
#
# Retro-cutting an old version (measured 2026-08-10): the Releases page sorts and
# dates each release by `createdAt`, which GitHub derives from the TAG OBJECT's
# tagger date — so create the tag with
#   GIT_COMMITTER_DATE=<the commit's own date> git tag -a vX.Y.Z <commit>
# and the release lands at its authentic point in the timeline. `publishedAt` is
# stamped by GitHub at publish time and cannot be backdated (API-visible only).
# NOTE: deleting a tag out from under an existing release turns that release into
# a DRAFT — re-dating means delete release -> retag -> re-run this script.
#
# The "Latest" badge follows the core component by default (that is what the
# README leads with); HUGINN_RELEASE_LATEST=1/0 overrides either way.
#
# Runs entirely locally via `gh` — deliberately no GitHub Actions dependency.
# Artifacts support gh's `path#Display Label` syntax.
set -euo pipefail
cd "$(dirname "$0")/.."

COMPONENT="${1:-}"; VERSION="${2:-}"; shift 2 2>/dev/null || {
  echo "usage: scripts/github-release.sh <core|app|desktop|appd> <version> [artifact ...]" >&2; exit 2; }
ARTIFACTS=("$@")

command -v gh >/dev/null || { echo "REFUSING: gh is not installed" >&2; exit 1; }
echo "$VERSION" | grep -qE '^[0-9]+\.[0-9]+\.[0-9]+$' \
  || { echo "REFUSING: version '$VERSION' is not x.y.z" >&2; exit 1; }

# TAG PREFIX = UPDATE CHANNEL. TITLE = LABEL. They are not the same thing and only
# one of them is free to change.
#
# A client only knows the prefix it was BUILT with. Rename one and the old client
# does not fail — it keeps checking, matches nothing, and reports "up to date"
# forever, with no way to be told the new name. So a channel rename is sequenced:
#
#   1. ship a release, under the OLD tag, whose client accepts BOTH names
#   2. wait until every device has actually taken it
#   3. only then start publishing under the new tag
#
# Step 1 shipped in app 2.73.0 (GithubReleases.MOBILE_TAG_PREFIXES). APP_TAG below
# is step 3, left on the old name deliberately.
#
# ⚠ FLIPPING APP_TAG IS NOT THE WHOLE JOB. devstore selects releases by
# `.source.tagPrefix` in the app's app.json ON DEVSERV; that has to move in the
# same breath or the store silently stops seeing new builds.
APP_TAG="app-v"          # -> "mobile-v" once every device is past 2.73.0
# The CLI channel renames with no ceremony: nothing consumes these tags. `huginn
# update` pulls client/huginn.sh from the repo CONTENTS api, and `huginn desktop`
# reads desktop-v — neither has ever looked at a bare v tag.
CLI_TAG="cli-v"

case "$COMPONENT" in
  core)    TAG="$CLI_TAG$VERSION";  TITLE="Huginn CLI $VERSION (Linux, macOS, Windows)"; CHANGELOG=CHANGELOG.md ;;
  app)     TAG="$APP_TAG$VERSION";  TITLE="Huginn Mobile $VERSION (Android)";            CHANGELOG=mobile/CHANGELOG.md ;;
  # ONE build, TWO platforms — an .exe and a .deb from the same jars. That is why
  # this channel is not split into windows-v/linux-v: it would be two tags, two
  # manifests and two updater channels describing one artifact set, and the files
  # already say which is which.
  desktop) TAG="desktop-v$VERSION"; TITLE="Huginn Desktop $VERSION (Windows, Linux)";    CHANGELOG=mobile/app-desktop/CHANGELOG.md ;;
  appd)    TAG="appd-v$VERSION";    TITLE="huginn-appd $VERSION (server)";               CHANGELOG=server/appd/CHANGELOG.md ;;
  *) echo "REFUSING: unknown component '$COMPONENT'" >&2; exit 2 ;;
esac

for a in "${ARTIFACTS[@]}"; do
  [ -f "${a%%#*}" ] || { echo "REFUSING: artifact '${a%%#*}' does not exist" >&2; exit 1; }
done

# ---------------------------------------------------------------- identity gate
# A built artifact can carry deployment identity the TREE no longer does: the
# Google Services plugin compiles google-services.json into the APK's string
# resources, so an APK names its Firebase project even though the repo stopped
# doing so. Uploading one to a public release would quietly undo that.
#
# The denylist lives OUTSIDE the tree on purpose -- a list of strings-not-to-publish
# is itself the leak once committed. Absent => the gate is a no-op, so a fresh
# clone still releases; present => it fails CLOSED, including if `strings` is gone.
DENY_SRC="${HUGINN_RELEASE_DENY_FILE:-/etc/huginn-appd/release-deny.txt}"
if [ "${#ARTIFACTS[@]}" -gt 0 ]; then
  DENY="$(mktemp)"
  # Blank lines and comments stripped: a single empty pattern would match every
  # artifact and turn a security gate into an unconditional refusal.
  [ -s "$DENY_SRC" ] && grep -vE '^[[:space:]]*(#|$)' "$DENY_SRC" >> "$DENY" || true
  # The hand-maintained denylist has drifted before: it once listed the (non-secret)
  # Firebase API key but NOT the surname-bearing project_id the scrub actually
  # existed to withhold. Derive the identity strings straight from the developer's
  # own google-services config so the scan can never omit the ids it guards. The
  # file is gitignored and never in the tree, so nothing secret is committed — the
  # values are read at release time from an untracked file, and on a fresh clone
  # (no config, no denylist) DENY stays empty and the gate is a no-op as before.
  GS="${HUGINN_GOOGLE_SERVICES_FILE:-mobile/app/google-services.json}"
  if [ -s "$GS" ]; then
    grep -oE '"(project_id|project_number|mobilesdk_app_id|current_key)"[[:space:]]*:[[:space:]]*"[^"]+"' "$GS" \
      | sed -E 's/.*:[[:space:]]*"([^"]+)"/\1/' >> "$DENY" || true
  fi
  [ -s "$DENY" ] && sort -u "$DENY" -o "$DENY" || true
  if [ -s "$DENY" ]; then
    command -v strings >/dev/null || {
      echo "REFUSING: $DENY_SRC exists but 'strings' is not installed -- cannot" >&2
      echo "          scan artifacts, and this gate does not fail open" >&2
      rm -f "$DENY"; exit 1; }
    for a in "${ARTIFACTS[@]}"; do
      f="${a%%#*}"
      # Decide on CONTENT, never on the pipeline's exit status. `strings | grep -q`
      # takes SIGPIPE when grep short-circuits on a hit, and under `set -o pipefail`
      # that reports the pipeline as FAILED -- so the gate would pass an artifact
      # exactly when it found something. `grep -c` reads to EOF and yields a count.
      hits="$(strings -a "$f" 2>/dev/null | grep -cFf "$DENY" || true)"
      if [ "${hits:-0}" -gt 0 ]; then
        echo "REFUSING: '$f' matches $hits line(s) in $DENY_SRC" >&2
        echo "          Deployment identity must not ship in a public release asset." >&2
        rm -f "$DENY"; exit 1
      fi
    done
    echo "[gh-release] identity gate: ${#ARTIFACTS[@]} artifact(s) clean"
  fi
  rm -f "$DENY"
fi

# ---------------------------------------------------------------- notes
NOTES="$(mktemp)"; trap 'rm -f "$NOTES"' EXIT
if [ -n "${HUGINN_RELEASE_NOTES_FILE:-}" ]; then
  cp "$HUGINN_RELEASE_NOTES_FILE" "$NOTES"
else
  # The section runs from the `## ` heading that carries this version (either
  # `## [x.y.z]` or `## x.y.z …`) to the next `## ` heading. `-v v=` rather than
  # interpolation so a version can never be parsed as awk syntax.
  awk -v v="$VERSION" '
    /^## / { on = (index($0, "[" v "]") || $2 == v || index($0, " " v " ") || $0 ~ ("^## " v "$")) }
    on' "$CHANGELOG" | tail -n +2 > "$NOTES"
  [ -s "$NOTES" ] || {
    echo "REFUSING: $CHANGELOG has no '## $VERSION' section (write the notes first," >&2
    echo "          or point HUGINN_RELEASE_NOTES_FILE at a notes file)" >&2; exit 1; }
fi

# ---------------------------------------------------------------- tag
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  echo "[gh-release] tag $TAG exists — leaving it where it is"
else
  REF="${HUGINN_RELEASE_REF:-HEAD}"
  git tag -a "$TAG" "$REF" -m "$TITLE"
  echo "[gh-release] tagged $TAG at $(git rev-parse --short "$TAG^{commit}")"
fi
git push origin "refs/tags/$TAG" >/dev/null 2>&1 || git push origin "refs/tags/$TAG"

# ---------------------------------------------------------------- release
LATEST_FLAG="--latest=false"
case "${HUGINN_RELEASE_LATEST:-}" in
  1) LATEST_FLAG="--latest" ;;
  0) LATEST_FLAG="--latest=false" ;;
  *) [ "$COMPONENT" = core ] && LATEST_FLAG="--latest" ;;
esac

if gh release view "$TAG" >/dev/null 2>&1; then
  echo "[gh-release] release $TAG exists — refreshing notes/title"
  gh release edit "$TAG" --title "$TITLE" --notes-file "$NOTES" "$LATEST_FLAG"
  if [ "${#ARTIFACTS[@]}" -gt 0 ]; then
    gh release upload "$TAG" "${ARTIFACTS[@]}" --clobber
  fi
else
  gh release create "$TAG" --verify-tag --title "$TITLE" --notes-file "$NOTES" \
    "$LATEST_FLAG" "${ARTIFACTS[@]}"
  echo "[gh-release] created $TAG"
fi
gh release view "$TAG" --json url,assets \
  --jq '"[gh-release] " + .url + "  assets: " + ([.assets[].name] | join(", "))'
