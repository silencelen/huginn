'use strict';
/**
 * A tmux SOCKET that vanished under a LIVING server.
 *
 * `tmux ls` says the same two things for "there has never been a server" and
 * "the server is alive but its socket file was unlinked": `no server running on
 * <socket>` (a stale file) or `error connecting to <socket> (No such file or
 * directory)` (no file). The daemon used to read both as "no sessions" — correct
 * at a fresh boot, catastrophic on a host whose /tmp was swept while the server
 * ran: the registry dropped every session, every project was archived because
 * its lead was "gone", and a SECOND, empty server was started inside the daemon
 * (the real one, with every session, kept running unreachable in its own unit).
 *
 * The tell is the unit: `systemctl show huginn-tmux.service -p MainPID --value`
 * is non-zero while the server lives. tmux(1) recreates its socket on SIGUSR1,
 * so the remedy is one signal to that pid, never a new server. Pure: the caller
 * runs tmux and systemctl and passes the text in.
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

/**
 * The socket is gone but the server is alive: a failure to OBSERVE, and a
 * SIGUSR1 to `mainPid` brings the socket back. False when tmux failed some other
 * way, or when there is genuinely no server (fresh boot: the unit has no pid).
 */
function socketLost(stderr, mainPid) {
  return noServer(stderr) && Number(mainPid) > 0;
}

module.exports = { noServer, unitMainPid, socketLost };
