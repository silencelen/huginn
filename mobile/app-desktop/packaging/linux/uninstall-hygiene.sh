### BEGIN huginn-uninstall-hygiene (injected by mobile/scripts/release-desktop.sh) ###
#
# ON PURGE ONLY. These are per-user dotfiles under $HOME. dpkg does not track
# them, they are not conffiles, and `apt remove` is meant to be undone by `apt
# install`. Purge is where Debian puts "and the configuration too", so purge is
# where this goes.
#
# THE SERVER FIRST, THE DISK SECOND — the ordering client/huginn-device keeps.
# A device row can only ever be retired with the token in the file that is
# about to be deleted, so the DELETE is attempted while that token still
# exists; wipe first and the row is stranded, reading "not reachable" in
# `huginn devices` forever. Best effort and never a gate: a package removal
# that hung on a sleeping daemon, or failed because of one, would be a far
# worse bug than a stale row.
#
# DEFENSIVE BY CONSTRUCTION: no `set -e`, every path quoted, every rm aimed at
# a name spelled out in full, nothing globbed, and exit 0 unconditionally. A
# maintainer script that fails leaves the package half-configured, and this one
# is doing housekeeping — it has no business being able to break an uninstall.
if [ "$1" = purge ]; then
  # The string value of "key": "value". Anchored on the KEY's own quotes rather
  # than a greedy `.*:` — the greedy form eats through the colon in
  # "http://127.0.0.1:8787" and hands back a fragment of the port.
  hyg_json() {
    [ -r "$1" ] || return 0
    LC_ALL=C grep -o "\"$2\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" "$1" 2>/dev/null \
      | head -1 | sed 's/^"[^"]*"[[:space:]]*:[[:space:]]*"//; s/"$//'
  }
  hyg_drop() {   # $1=base url  $2=device id  $3=bearer token
    [ -n "$1" ] && [ -n "$2" ] && [ -n "$3" ] || return 0
    command -v curl >/dev/null 2>&1 || return 0
    curl -sS -o /dev/null --max-time 5 -X DELETE \
      -H "Authorization: Bearer $3" "${1%/}/v1/devices/$2" >/dev/null 2>&1
    return 0
  }
  # uid >= 1000 is "a person", and root is added because plenty of these boxes
  # run as root and would otherwise be the one home this never cleaned. nobody
  # (65534) is excluded: it has a home path and no business owning one.
  getent passwd 2>/dev/null \
    | awk -F: '($3 >= 1000 && $3 < 65534) || $3 == 0 { print $6 }' \
    | while IFS= read -r h; do
    case "$h" in
      /*) ;;
      *) continue ;;                 # relative or empty: not a home, not ours
    esac
    [ -d "$h" ] || continue

    hyg_cfg="$h/.config/huginn-desktop-kt"
    hyg_drop "$(hyg_json "$hyg_cfg/settings.json" baseUrl)" \
             "$(hyg_json "$hyg_cfg/settings.json" deviceId)" \
             "$(hyg_json "$hyg_cfg/settings.json" token)"
    # The SECOND enrolment. A machine that also serves local models has a row of
    # its own (<host>-llm, scope generate) with its own id and its own copy of
    # the token; retiring one and not the other leaves a ghost that shows up
    # only in the model picker.
    hyg_ltier="$h/.config/huginn-local"
    hyg_drop "$(hyg_json "$hyg_ltier/device/device.json" url)" \
             "$(hyg_json "$hyg_ltier/device/device.json" id)" \
             "$(head -1 "$hyg_ltier/device/appd-token" 2>/dev/null | tr -d '[:space:]')"

    rm -rf "$hyg_cfg"
    rm -rf "$h/.cache/huginn-desktop-kt"
    rm -rf "$hyg_ltier"
    rm -f  "$h/.local/share/applications/huginn-desktop-kt.desktop"
    # NAMED ONE BY ONE. ~/.huginn also holds a separately installed base client
    # (client/install.sh) and its .bak files, and that install is not this
    # package's to undo — a wildcard here would take huginn.sh with it and
    # leave somebody's .bashrc sourcing a file that no longer exists.
    for hyg_f in huginn-device huginn-local; do
      rm -f "$h/.huginn/$hyg_f" "$h/.huginn/$hyg_f.bak" \
            "$h/.huginn/$hyg_f.tmp.js" "$h/.huginn/$hyg_f.appsync.tmp.js"
    done
    # Unit FILES only, and never systemctl. A root maintainer script cannot
    # reach another user's session bus, and `systemctl --user` from here would
    # either fail or — worse — act on root's own manager instead.
    for hyg_u in huginn-device huginn-local-llm huginn-local-runner; do
      rm -f "$h/.config/systemd/user/$hyg_u.service" \
            "$h/.config/systemd/user/default.target.wants/$hyg_u.service"
    done
  done
fi
### END huginn-uninstall-hygiene ###
