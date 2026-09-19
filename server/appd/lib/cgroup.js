'use strict';
/**
 * Whose journal is this, really.
 *
 * ⚠ THE FLOOD M5 IS ABOUT (round-2 review, 2026-09-19). `journalctl -u
 * huginn-appd --since -24h` on the live host is dominated by thousands of lines
 * nothing in this daemon wrote:
 *
 *   (7.6.5:11:80) [ScriptBlock_Compile_Detail:ExecuteCommand.Create.Warning]
 *   Creating Scriptblock text (3 of 4): … Path: /root/huginn-work/client/huginn.ps1
 *
 * Whole `huginn.ps1` bodies, several hundred lines per run. The walker read it as
 * child stdio inheritance; it is not. PowerShell on Linux logs to SYSLOG, and
 * journald files a syslog message under whichever unit owns the sender's CGROUP.
 * Every `pwsh` on this host runs inside a Claude Code session, every session is a
 * tmux pane, and a tmux SERVER first started by this daemon daemonises into this
 * daemon's cgroup — so the whole tree is `huginn-appd.service` as far as journald
 * is concerned. Measured: a shell inside a session reads
 * `0::/system.slice/huginn-appd.service`.
 *
 * Two halves to the answer and this module is the diagnosis half:
 *
 *   * the unit sets `SyslogIdentifier=huginn-appd`, so the daemon's OWN lines can
 *     be read on their own with `journalctl -u huginn-appd -t huginn-appd`. A
 *     child's genuine errors are still in the unit's journal, under its own
 *     identifier, which is where they belong — nothing is filtered away.
 *   * `ensureTmuxServerScope` already starts a NEW server in `huginn-tmux.scope`
 *     for exactly this family of reasons (a deploy's SIGTERM, ProtectSystem
 *     leaking into panes). It deliberately leaves a RUNNING server where it is,
 *     because moving one strands its children — so a server inherited before that
 *     existed stays in the unit's cgroup indefinitely, and nothing said so. That
 *     is one journal line, written once, with the remedy in it.
 *
 * Pure: the caller does the /proc reads and passes the text in.
 */

/**
 * The cgroup path out of `/proc/<pid>/cgroup`, or null.
 *
 * cgroup v2 writes one line, `0::/system.slice/huginn-appd.service`. v1 writes
 * one per controller, `N:name:/path`; the unified line (hierarchy 0) is the one
 * journald files by, and a v1-only host is read from its `name=systemd` line
 * instead. Anything unrecognisable is null — "I could not tell" must never read
 * as "they match".
 */
function cgroupPath(text) {
  const lines = String(text || '').split('\n');
  let v1 = null;
  for (const line of lines) {
    const m = /^(\d+):([^:]*):(.*)$/.exec(line.trim());
    if (!m) continue;
    if (m[1] === '0' && m[2] === '') return m[3] || null;
    if (/(^|,)name=systemd(,|$)/.test(m[2])) v1 = m[3] || null;
  }
  return v1;
}

/**
 * The systemd unit a cgroup path belongs to, or null for one that names none.
 *
 * ⚠ THE LAST NAMED SEGMENT, NOT THE FIRST. `/system.slice/huginn-appd.service`
 * starts with a SLICE, and a rule that stopped at the first match would call
 * every unit on the host `system.slice`. The deepest one is the unit; the slices
 * above it are where it lives.
 */
function unitOf(text) {
  const p = cgroupPath(text);
  if (!p) return null;
  let unit = null;
  for (const seg of p.split('/')) {
    if (/\.(?:service|scope|slice)$/.test(seg)) unit = seg;
  }
  return unit;
}

/**
 * Do these two processes sit in the same cgroup?
 *
 * Both readable and equal, or false. A pid that has gone, a /proc this daemon
 * cannot read, a format nobody recognises — all of them are "no claim", because
 * the only thing this answer is used for is writing a diagnostic, and a
 * diagnostic that fires on a guess is worse than none.
 */
function sameCgroup(oursText, theirsText) {
  const a = cgroupPath(oursText);
  const b = cgroupPath(theirsText);
  return !!a && !!b && a === b;
}

/**
 * The one line an operator needs when the unit's journal is full of other
 * people's programs.
 */
function foreignJournalLogLine(unit, scope) {
  return `tmux: the server shares this daemon's cgroup (${unit || 'this unit'}), so everything `
    + `running in a session — Claude Code, and anything it starts — files its syslog under `
    + `\`journalctl -u huginn-appd\`. PowerShell alone writes whole script bodies there. `
    + `Read this daemon's own lines with \`journalctl -u huginn-appd -t huginn-appd\`; the durable `
    + `fix is to let the tmux server restart into ${scope}.scope once no sessions are left.`;
}

module.exports = { cgroupPath, unitOf, sameCgroup, foreignJournalLogLine };
