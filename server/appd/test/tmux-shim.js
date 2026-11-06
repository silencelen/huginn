'use strict';

// A tmux and a systemctl that lie the way a loaded host's did (2026-11-06), for
// the daemon a suite starts. Put `env.PATH` and the rest of `env` into the
// daemon's environment; with no control file set, the tmux here IS the real
// tmux and nothing is different.
//
// The control files, one per lie, each changing ONE subcommand:
//
//   list-noserver   `list-sessions` fails with tmux's own words for "there is no
//                   socket": error connecting to <socket> (No such file or
//                   directory). The server and its sessions stay up.
//   list-hide       `list-sessions` leaves out every row that contains one of the
//                   file's lines, and succeeds: a listing that is wrong.
//   has-refuse      `has-session` whose arguments contain one of the file's lines
//                   fails the way an unreachable server does (Connection refused).
//   unit            what the systemctl stand-in (HUGINN_APPD_SYSTEMCTL) answers
//                   for the tmux unit's MainPID: a pid, `0`, or `fail` (and by
//                   default) a systemd that will not answer.
//
// ⚠ The only pid a suite may ever put in `unit` is its OWN private tmux
// server's: a "socket lost" verdict makes the daemon send that pid SIGUSR1.
//
// `claude` is shadowed too, with a stand-in that runs nothing, for any session
// the daemon starts (a reboot restore).

const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const REAL_TMUX = execFileSync('/bin/sh', ['-c', 'command -v tmux'], { encoding: 'utf8' }).trim();

/** Where tmux keeps this user's sockets for `-L <name>`. */
function socketPathFor(sock) {
  return path.join(process.env.TMUX_TMPDIR || '/tmp', `tmux-${process.getuid()}`, sock);
}

/** Write the stand-ins into [dir]; returns `{ set, clear, env }`. */
function lyingTmux(dir, { sock }) {
  const ctl = path.join(dir, 'ctl');
  fs.mkdirSync(ctl, { recursive: true });
  const sockPath = socketPathFor(sock);
  fs.writeFileSync(path.join(dir, 'tmux'), [
    '#!/bin/sh',
    `real='${REAL_TMUX}'`,
    'sub=',
    'for a in "$@"; do',
    '  case "$a" in list-sessions|has-session) sub=$a; break ;; esac',
    'done',
    'if [ "$sub" = list-sessions ] && [ -f "$HG_SHIM/list-noserver" ]; then',
    `  echo 'error connecting to ${sockPath} (No such file or directory)' >&2`,
    '  exit 1',
    'fi',
    'if [ "$sub" = has-session ] && [ -s "$HG_SHIM/has-refuse" ] \\',
    '    && printf \'%s\\n\' "$*" | grep -q -F -f "$HG_SHIM/has-refuse"; then',
    `  echo 'error connecting to ${sockPath} (Connection refused)' >&2`,
    '  exit 1',
    'fi',
    'if [ "$sub" = list-sessions ] && [ -s "$HG_SHIM/list-hide" ]; then',
    '  out=$("$real" "$@"); st=$?',
    '  printf \'%s\\n\' "$out" | grep -v -F -f "$HG_SHIM/list-hide"',
    '  exit $st',
    'fi',
    'exec "$real" "$@"',
    '',
  ].join('\n'), { mode: 0o755 });
  fs.writeFileSync(path.join(dir, 'systemctl'), [
    '#!/bin/sh',
    'mode=$(cat "$HG_SHIM/unit" 2>/dev/null)',
    'case "$mode" in',
    "  ''|fail) echo 'Failed to connect to bus: Connection timed out' >&2; exit 1 ;;",
    '  *) echo "$mode" ;;',
    'esac',
    '',
  ].join('\n'), { mode: 0o755 });
  fs.writeFileSync(path.join(dir, 'claude'), '#!/bin/sh\nexec cat >/dev/null\n', { mode: 0o755 });

  return {
    /** Turn a lie on; written whole (tmp + rename) so the shim never reads half a file. */
    set(name, lines = ['on']) {
      const f = path.join(ctl, name);
      fs.writeFileSync(`${f}.tmp`, `${lines.join('\n')}\n`);
      fs.renameSync(`${f}.tmp`, f);
    },
    clear(name) { fs.rmSync(path.join(ctl, name), { force: true }); },
    env: {
      PATH: `${dir}:${process.env.PATH}`,
      HG_SHIM: ctl,
      HUGINN_APPD_SYSTEMCTL: path.join(dir, 'systemctl'),
    },
  };
}

module.exports = { lyingTmux, socketPathFor, REAL_TMUX };
