#!/usr/bin/env bash
# test-ssh-identityfile.sh - the installers write the `Host huginn` stanza so
# ssh can read it from a home with a space in its path, and `huginn uninstall`
# reads its key back. (`huginn setup ssh`, the core's writer, is held to the
# same by core/cmd/huginn/sshconfig_test.go.)
#
# WHY THIS EXISTS: install.ps1 and install.sh wrote `IdentityFile <path>`
# unquoted. A Windows profile is often C:\Users\First Last, and OpenSSH reads
# that line as "extra arguments at end of line": a fatal error for EVERY host
# in ~/.ssh/config, git over ssh included, not only huginn, and the installer
# then reported it as a host it could not reach. The path is written in double
# quotes now, so the uninstallers have to read a quoted path whole.
#
# Each installer runs end to end under an empty environment, in a scratch home
# named "First Last", against a stub ssh that cannot connect; ssh's own parser
# (`ssh -F <file> -G <host>`, which connects nowhere) then reads the result.
# Nothing outside the scratch directory is read or written.
#
# Usage: scripts/test-ssh-identityfile.sh [tree]
#   tree defaults to this checkout. Point it at an export of an older commit
#   (git archive <rev> client | tar -x -C DIR) to see the gate fail there.
set -uo pipefail
TREE="$(cd "${1:-$(dirname "${BASH_SOURCE[0]}")/..}" && pwd)" || exit 1

FAIL=0
ok()   { echo "  ok    $*"; }
bad()  { echo "  FAIL  $*" >&2; FAIL=1; }
skip() { echo "  SKIP  $*  <-- not a pass" >&2; }

T="$(mktemp -d)"
trap 'rm -rf "$T"' EXIT
REAL_SSH="$(command -v ssh || true)"
PWSH="$(command -v pwsh || true)"
mkdir -p "$T/bin"
cat > "$T/bin/ssh" <<'STUB'
#!/bin/sh
echo "ssh: connect to host 192.0.2.10 port 22: Connection refused" >&2
exit 255
STUB
chmod +x "$T/bin/ssh"

seed() {   # $1 = a scratch home: a key to reuse, and somebody's own ssh config
  mkdir -p "$1/.ssh"
  printf 'not a real key\n' > "$1/.ssh/id_ed25519"
  printf 'ssh-ed25519 AAAA alex@example.net\n' > "$1/.ssh/id_ed25519.pub"
  printf 'Host git.example.net\n  User git\n' > "$1/.ssh/config"
}
reads() {  # $1 = the config, $2 = the key path huginn's stanza must name, $3 = who wrote it
  if [ -z "$REAL_SSH" ]; then skip "$3: ssh's own parser (no ssh here)"; return; fi
  if "$REAL_SSH" -F "$1" -G git.example.net </dev/null >/dev/null 2>"$T/err"; then
    ok "$3: the person's other hosts still parse"
  else
    bad "$3: ssh can no longer read the config for another host: $(cat "$T/err")"
  fi
  if "$REAL_SSH" -F "$1" -G huginn </dev/null 2>/dev/null | grep -qxF -- "identityfile $2"; then
    ok "$3: and ssh reads huginn's key as the whole path"
  else
    bad "$3: ssh reads huginn's key as: $("$REAL_SSH" -F "$1" -G huginn </dev/null 2>&1 | grep -i identityfile | head -2)"
  fi
}

echo "[1/3] install.sh, in a home with a space in it"
H="$T/sh/First Last"; seed "$H"
env -i HOME="$H" PATH="$T/bin:/usr/bin:/bin" bash "$TREE/client/install.sh" 192.0.2.10 </dev/null >"$T/sh.out" 2>&1 \
  || bad "install.sh exited non-zero: $(tail -3 "$T/sh.out")"
grep -qxF -- "  IdentityFile \"$H/.ssh/id_ed25519\"" "$H/.ssh/config" \
  && ok "install.sh: the key path is written in double quotes" \
  || bad "install.sh wrote: $(grep -i identityfile "$H/.ssh/config")"
reads "$H/.ssh/config" "$H/.ssh/id_ed25519" install.sh

echo "[2/3] install.ps1, the same"
if [ -z "$PWSH" ]; then
  skip "install.ps1 (no pwsh)"
else
  H="$T/ps/First Last"; seed "$H"
  # pwsh on Linux builds "$HOME\.ssh\id_ed25519" the way Windows would, so the
  # path ssh reads keeps those backslashes.
  env -i HOME="$H" PATH="$T/bin:/usr/bin:/bin" XDG_CONFIG_HOME="$H/.config" XDG_CACHE_HOME="$H/.cache" \
    XDG_DATA_HOME="$H/.local/share" "$PWSH" -NoProfile -NonInteractive -File "$TREE/client/install.ps1" \
    -HuginnHost 192.0.2.10 </dev/null >"$T/ps.out" 2>&1 || bad "install.ps1 exited non-zero: $(tail -3 "$T/ps.out")"
  KEY="$H\\.ssh\\id_ed25519"
  tr -d '\r' < "$H/.ssh/config" | grep -qxF -- "  IdentityFile \"$KEY\"" \
    && ok "install.ps1: the key path is written in double quotes" \
    || bad "install.ps1 wrote: $(grep -i identityfile "$H/.ssh/config")"
  reads "$H/.ssh/config" "$KEY" install.ps1
fi

echo "[3/3] huginn uninstall --all reads a quoted key path back, and ends the stanza where ssh does"
mkhome() {   # $1 = a scratch home holding a quoted huginn key and the client
  mkdir -p "$1/.ssh" "$1/.huginn"
  cp "$TREE/client/huginn.sh" "$TREE/client/huginn.ps1" "$1/.huginn/"
  printf 'not a real key\n' > "$1/.ssh/id_ed25519_huginn"
  printf 'ssh-ed25519 AAAA alex@example.net\n' > "$1/.ssh/id_ed25519_huginn.pub"
  # A Match block after the stanza ends it, as ssh reads the file, and the
  # comment heading that block is its, not the stanza's.
  printf 'Host git.example.net\n  User git\n\nHost huginn\n  HostName 192.0.2.10\n  IdentityFile "%s/.ssh/id_ed25519_huginn"\n# work hosts below\nMatch host *.corp.example\n  User alex\n' "$1" \
    > "$1/.ssh/config"
}
took() {     # $1 = the home, $2 = which client -> huginn's key went, the other stanza stayed
  if [ ! -e "$1/.ssh/id_ed25519_huginn" ] && [ ! -e "$1/.ssh/id_ed25519_huginn.pub" ] \
     && grep -q '^Host git.example.net' "$1/.ssh/config" && ! grep -q huginn "$1/.ssh/config" \
     && grep -q '^# work hosts below$' "$1/.ssh/config" && grep -q '^Match host \*\.corp\.example$' "$1/.ssh/config" \
     && grep -q '^  User alex$' "$1/.ssh/config"; then
    ok "$2: huginn's own key goes with its stanza, and nothing else does"
  else
    bad "$2: left $(cd "$1/.ssh" && ls | tr '\n' ' ')and the config: $(tr '\n' '|' < "$1/.ssh/config")"
  fi
}
# With a core installed here the shell client hands the uninstall to it, which
# would act on this machine rather than on a scratch home.
if [ -x /usr/bin/huginn ]; then
  skip "sh: huginn uninstall (a huginn core is installed here, and the client would hand over to it)"
else
  H="$T/un-sh/First Last"; mkhome "$H"
  env -i HOME="$H" PATH="/usr/bin:/bin" bash -c '. "$HOME/.huginn/huginn.sh"; huginn uninstall --all --yes' \
    >"$T/un-sh.out" 2>&1
  took "$H" sh
fi
if [ -z "$PWSH" ]; then
  skip "ps1: huginn uninstall (no pwsh)"
else
  H="$T/un-ps/First Last"; mkhome "$H"
  env -i HOME="$H" PATH="/usr/bin:/bin" XDG_CONFIG_HOME="$H/.config" XDG_CACHE_HOME="$H/.cache" \
    XDG_DATA_HOME="$H/.local/share" "$PWSH" -NoProfile -NonInteractive \
    -Command '. "$HOME/.huginn/huginn.ps1"; huginn uninstall --all --yes' >"$T/un-ps.out" 2>&1
  took "$H" ps1
fi

echo
[ "$FAIL" = 0 ] && echo "ssh-identityfile gates: PASS" || { echo "ssh-identityfile gates: FAIL" >&2; exit 1; }
