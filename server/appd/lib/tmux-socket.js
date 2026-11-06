'use strict';
/**
 * What a failed tmux call says about the host: nothing there, or nothing KNOWN.
 *
 * ⚠ 2026-10-27: A SOCKET THAT VANISHED UNDER A LIVING SERVER. `tmux ls` says the
 * same two things for "there has never been a server" and "the server is alive
 * but its socket file was unlinked": `no server running on <socket>` (a stale
 * file) or `error connecting to <socket> (No such file or directory)` (no file).
 * The daemon used to read both as "no sessions" — correct at a fresh boot,
 * catastrophic on a host whose /tmp was swept while the server ran: the registry
 * dropped every session, every project was archived because its lead was "gone",
 * and a SECOND, empty server was started inside the daemon (the real one, with
 * every session, kept running unreachable in its own unit).
 *
 * The tell is the unit: `systemctl show huginn-tmux.service -p MainPID --value`
 * is non-zero while the server lives. tmux(1) recreates its socket on SIGUSR1,
 * so the remedy is one signal to that pid, never a new server.
 *
 * ⚠⚠ 2026-11-06: AND SYSTEMD MAY NOT ANSWER EITHER. On a host under a backup's
 * load, with swap nearly full, one listing failed the no-server way while the
 * server and its 23 sessions were fine — and `systemctl show` failed too. The
 * unit's pid read as "none" for EVERY "could not tell", so "tmux could not be
 * reached" plus "systemd could not be asked" came out as "this host has no
 * sessions", silently, and one reconcile tick dropped every member of five
 * projects and archived all five. An empty host is now something OBSERVED: tmux
 * says there is no server in its own words AND systemd answers that the unit
 * has no main process. Anything less is a failure to observe, and the caller
 * treats it as unknown — never as empty.
 *
 * Pure: the caller runs tmux and systemctl and passes what they returned in.
 */

/** `tmux ls` failed for want of a server or a socket (not a timeout, not EAGAIN). */
function noServer(stderr) {
  return /no server running|error connecting to .*\((No such file or directory|Connection refused)\)/i
    .test(String(stderr || ''));
}

/** The pid out of `systemctl show <unit> -p MainPID --value` (or `MainPID=N`), else null. */
function unitMainPid(text) {
  const m = /^(?:MainPID=)?\s*(\d+)\s*$/.exec(String(text || '').trim());
  if (!m) return null;
  const pid = Number(m[1]);
  return pid > 0 ? pid : null;
}

/** The first line of a tool's stderr, short enough for a log line. */
function firstLine(text) {
  return String(text || '').trim().split('\n')[0].slice(0, 120);
}

/** Why a child run through execFile failed, in a few words. */
function whyFailed(err, stderr) {
  if (!err) return '';
  if (err.killed || err.signal) return `timed out (${err.signal || 'killed'})`;
  return firstLine(stderr) || String(err.code || err.message || 'failed');
}

/**
 * systemd's answer about the tmux unit, from `systemctl show <unit> -p MainPID
 * --value` as the daemon's run() returns it (`{ err, stdout, stderr }`):
 *
 *   { pid: N }     systemd ANSWERED. N > 0: the unit's server is alive. 0: the
 *                  unit has no main process — stopped, or never started (a unit
 *                  systemd does not know answers `0` with LoadState=not-found).
 *   { pid: null }  systemd could not be asked: the call failed, timed out, or
 *                  printed something that is not a pid. Not "no pid" — unknown.
 *
 * `why` says which, for the log line that explains the decision.
 */
function unitAnswer(r) {
  const { err, stdout, stderr } = r || {};
  if (err) {
    const timedOut = err.killed || err.signal;
    return { pid: null, why: timedOut ? `systemctl ${whyFailed(err, stderr)}` : `systemctl failed: ${whyFailed(err, stderr)}` };
  }
  const text = String(stdout || '').trim();
  const m = /^(?:MainPID=)?\s*(\d+)$/.exec(text);
  if (!m) return { pid: null, why: `systemctl answered ${JSON.stringify(text.slice(0, 60))}` };
  const pid = Number(m[1]);
  return { pid, why: pid > 0 ? `has main process ${pid}` : 'has no main process' };
}

/**
 * The socket is gone but the server is alive: a failure to OBSERVE, and a
 * SIGUSR1 to `mainPid` brings the socket back. False when tmux failed some other
 * way, or when there is genuinely no server (fresh boot: the unit has no pid).
 */
function socketLost(stderr, mainPid) {
  return noServer(stderr) && Number(mainPid) > 0;
}

/**
 * What a failed `tmux list-sessions` (or `tmux ls`) means, given systemd's
 * answer about the unit ([unitAnswer], or `{ pid: 0 }` where no unit can hold
 * the server):
 *
 *   'empty'    tmux says there is no server, in its own words, AND the unit has
 *              no main process: the host has no sessions (a fresh boot).
 *   'lost'     tmux says there is no server but the unit's server is alive: the
 *              socket is gone (2026-10-27). Unknown, and one SIGUSR1 fixes it.
 *   'unknown'  anything else — tmux failed some other way, or systemd could not
 *              be asked (2026-11-06). Never read as an empty host.
 */
function listingVerdict(stderr, unit) {
  if (!noServer(stderr)) return 'unknown';
  const pid = unit ? unit.pid : null;
  if (pid === null || pid === undefined || !Number.isInteger(Number(pid)) || Number(pid) < 0) return 'unknown';
  return Number(pid) > 0 ? 'lost' : 'empty';
}

/**
 * `tmux has-session -t =<name>`, read as `{ gone, why }`:
 *
 *   gone: true   a tmux SERVER answered that it has no session of that name
 *                ("can't find session: <name>"). The only answer that proves an
 *                ending, so the only one a destructive write may follow.
 *   gone: false  the session exists.
 *   gone: null   anything else: no server reachable ("no server running",
 *                "error connecting to …"), a timeout, a fork that failed. Under
 *                the conditions that make a listing lie, these are exactly the
 *                answers a probe gets, so they hold the change.
 */
function probeVerdict(r) {
  const { err, stderr } = r || {};
  if (!err) return { gone: false, why: 'tmux has it' };
  const text = String(stderr || '');
  if (!noServer(text) && /can't find session|no such session|session not found/i.test(text)) {
    return { gone: true, why: firstLine(text) };
  }
  return { gone: null, why: whyFailed(err, text) };
}

module.exports = { noServer, unitMainPid, unitAnswer, socketLost, listingVerdict, probeVerdict, firstLine };
