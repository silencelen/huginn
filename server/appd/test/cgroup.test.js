'use strict';
// Whose journal `journalctl -u huginn-appd` actually is (M5, round-2 review).
//
// THE FLOOD. 24 hours of the live unit's journal was dominated by lines nothing
// in this daemon wrote — whole `huginn.ps1` bodies, several hundred per run:
//
//   (7.6.5:11:80) [ScriptBlock_Compile_Detail:ExecuteCommand.Create.Warning]
//   Creating Scriptblock text (3 of 4): … Path: /root/huginn-work/client/huginn.ps1
//
// Not stdio inheritance, which is what it looks like. PowerShell on Linux logs
// to SYSLOG, and journald files a syslog message under whichever unit owns the
// SENDER'S cgroup. Every `pwsh` on this host runs inside a Claude Code session,
// every session is a tmux pane, and a tmux server first started by this daemon
// daemonises into this daemon's cgroup — so journald reads the whole tree as
// `huginn-appd.service`. Measured on the host: a shell inside a session reads
// `0::/system.slice/huginn-appd.service`.
//
// The unit now sets `SyslogIdentifier=huginn-appd`, so `journalctl -u
// huginn-appd -t huginn-appd` is the daemon's own log while a child's genuine
// errors stay in the unit's journal under their own identifier — nothing is
// filtered away. What this file pins is the DIAGNOSIS: the daemon notices that
// the server shares its cgroup and says so once, with the remedy, instead of
// leaving an operator to work out why a foreign program is in their journal.

const { test } = require('node:test');
const assert = require('node:assert');
const c = require('../lib/cgroup');

// Verbatim from this host, 2026-09-19.
const V2_UNIT = '0::/system.slice/huginn-appd.service\n';
const V2_SCOPE = '0::/system.slice/huginn-tmux.scope\n';
const V2_USER = '0::/user.slice/user-0.slice/session-31.scope\n';
const V1 = [
  '11:blkio:/system.slice/huginn-appd.service',
  '3:name=systemd:/system.slice/huginn-appd.service',
  '1:cpuset:/',
].join('\n');

test('cgroupPath reads the unified line, and falls back to name=systemd on v1', () => {
  assert.equal(c.cgroupPath(V2_UNIT), '/system.slice/huginn-appd.service');
  assert.equal(c.cgroupPath(V1), '/system.slice/huginn-appd.service',
    'a v1-only host is read from the hierarchy journald files by');
  // ⚠ "I COULD NOT TELL" IS NEVER "THEY MATCH". A pid that has gone, a /proc
  // this daemon cannot read, a format nobody recognises — the only thing this
  // answer drives is a journal line, and one that fires on a guess is worse
  // than none.
  assert.equal(c.cgroupPath(''), null);
  assert.equal(c.cgroupPath(null), null);
  assert.equal(c.cgroupPath('not a cgroup file at all'), null);
});

test('unitOf names the unit, and nothing when the path is not in one', () => {
  assert.equal(c.unitOf(V2_UNIT), 'huginn-appd.service');
  assert.equal(c.unitOf(V2_SCOPE), 'huginn-tmux.scope');
  assert.equal(c.unitOf(V2_USER), 'session-31.scope');
  assert.equal(c.unitOf('0::/\n'), null);
  assert.equal(c.unitOf(''), null);
});

test('sameCgroup is true only when BOTH are readable and equal', () => {
  assert.equal(c.sameCgroup(V2_UNIT, V2_UNIT), true, 'the flood: the server is in our cgroup');
  assert.equal(c.sameCgroup(V2_UNIT, V2_SCOPE), false,
    'a server in its own scope files its children somewhere else, which is the fix');
  assert.equal(c.sameCgroup(V2_UNIT, V2_USER), false, 'a server started from a login shell is not ours');
  assert.equal(c.sameCgroup(V2_UNIT, ''), false, 'an unreadable /proc makes no claim');
  assert.equal(c.sameCgroup('', ''), false, 'and two unreadable ones make no claim twice');
  assert.equal(c.sameCgroup(V1, V2_UNIT), true, 'v1 and v2 spellings of the same place agree');
});

test('the journal line names the cause, the reading that works, and the real fix', () => {
  const line = c.foreignJournalLogLine('huginn-appd.service', 'huginn-tmux');
  assert.match(line, /huginn-appd\.service/, 'which unit is being polluted');
  assert.match(line, /PowerShell/, 'the program an operator will actually be staring at');
  assert.match(line, /journalctl -u huginn-appd -t huginn-appd/,
    'the command that reads this daemon alone — the half that works today');
  assert.match(line, /huginn-tmux\.scope/, 'and the durable fix, by name');
  assert.ok(line.length < 600, 'one line, not an essay in the journal');
});
