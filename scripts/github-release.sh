#!/usr/bin/env bash
# Cut (or refresh) a GitHub Release for one huginn component.
#
#   scripts/github-release.sh <core|app|desktop|appd> <version> [artifact ...]
#
# One repo, four independently-versioned components, so tags are namespaced:
#   core    -> v<version>          (the CLI + server core; CHANGELOG.md)
#   app     -> app-v<version>      (Android;    mobile/CHANGELOG.md)
#   desktop -> desktop-v<version>  (Compose;    mobile/app-desktop/CHANGELOG.md)
#   appd    -> appd-v<version>     (the daemon; notes via HUGINN_RELEASE_NOTES_FILE)
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

case "$COMPONENT" in
  core)    TAG="v$VERSION";         TITLE="huginn-cli v$VERSION";      CHANGELOG=CHANGELOG.md ;;
  app)     TAG="app-v$VERSION";     TITLE="Huginn (Android) $VERSION"; CHANGELOG=mobile/CHANGELOG.md ;;
  desktop) TAG="desktop-v$VERSION"; TITLE="Huginn Desktop $VERSION";   CHANGELOG=mobile/app-desktop/CHANGELOG.md ;;
  appd)    TAG="appd-v$VERSION";    TITLE="huginn-appd $VERSION";      CHANGELOG=mobile/CHANGELOG.md ;;
  *) echo "REFUSING: unknown component '$COMPONENT'" >&2; exit 2 ;;
esac

for a in "${ARTIFACTS[@]}"; do
  [ -f "${a%%#*}" ] || { echo "REFUSING: artifact '${a%%#*}' does not exist" >&2; exit 1; }
done

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
