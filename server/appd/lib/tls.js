'use strict';
/**
 * ─── TLS FOR THE DAEMON, WITH NO CA AND NO SETUP ───────────────────────────
 *
 * Before 3.11.0 appd spoke plain HTTP and the bearer token — root-equivalent on
 * this host — crossed whatever network sat between a client and the daemon in
 * the clear. The old justification ("the tailnet is the trust boundary") only
 * ever held for people running Tailscale. This is a public project: the daemon
 * has to be safe on a flat home LAN, a coffee-shop Wi-Fi or a VPS with no
 * overlay at all, so TLS is ON BY DEFAULT and needs nothing from the operator.
 *
 * ⚠ THE TRUST MODEL IS NOT A CA, AND THAT IS DELIBERATE. The daemon mints its
 * own self-signed certificate on first start. A client cannot validate that
 * against anything, so it validates the DAEMON instead, with the one secret
 * both sides already share — the bearer token — and without sending it:
 *
 *   client picks nonce N, opens TLS, sees certificate C, fp = sha256(DER(C))
 *   GET /v1/challenge?nonce=N   (no token)
 *   daemon answers  tls.proof = HMAC(token, "huginn-tls-v1:" + N + ":" + fp(own cert))
 *   client computes HMAC(token, "huginn-tls-v1:" + N + ":" + fp(C THAT IT SAW))
 *
 * A man in the middle has to show the client its OWN certificate M; the proof
 * the client then needs is over fp(M), which only a token holder can make. The
 * real daemon's proof is over the real fingerprint and does not transfer. On a
 * match the client PINS fp(C) and from then on sends the token only over a
 * connection presenting that exact certificate; a changed certificate is
 * re-proved the same way before it is trusted, never accepted on sight.
 *
 * ⚠ The fingerprint the daemon reports beside the proof is informational. A
 * client must hash what its own handshake saw — trusting the reported value
 * would let the man in the middle report the real one.
 *
 * The input is domain-separated from the legacy proof (HMAC over the bare hex
 * nonce): ':' never appears in a valid nonce, so one proof can never stand in
 * for the other.
 *
 * Operators who want a CA-issued certificate set HUGINN_APPD_TLS_CERT and
 * HUGINN_APPD_TLS_KEY; clients pin it through the same proof.
 */
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { execFileSync } = require('node:child_process');

const BOUND_PREFIX = 'huginn-tls-v1:';

/** Lower-case hex SHA-256 of the certificate's DER — what every client hashes. */
function fingerprintOf(certPem) {
  return new crypto.X509Certificate(certPem).fingerprint256.replace(/:/g, '').toLowerCase();
}

/** The channel-bound proof. `fp` is ALWAYS this daemon's own certificate. */
function boundProof(token, nonce, fp) {
  return crypto.createHmac('sha256', token).update(`${BOUND_PREFIX}${nonce}:${fp}`, 'utf8').digest('hex');
}

/**
 * The certificate and key to serve, minting a self-signed pair on first start.
 *
 * Returns { cert, key, fp, certFile, keyFile, minted } or throws with a reason a
 * person can act on. The key is written 0600 in a 0700 directory, through a temp
 * name and a rename, so a crash mid-write never leaves a half key that fails
 * every later start.
 *
 * P-256, ten years: a self-signed certificate is trusted by pin, not by expiry,
 * and a short lifetime would only make every client re-prove on a schedule.
 * Rotation is "delete the two files and restart"; clients re-prove the new one.
 */
function loadOrCreate({ dataDir, certFile, keyFile, log = () => { } }) {
  const dir = path.join(dataDir, 'tls');
  const cf = certFile || path.join(dir, 'cert.pem');
  const kf = keyFile || path.join(dir, 'key.pem');
  const operatorSupplied = Boolean(certFile || keyFile);
  if (operatorSupplied && !(certFile && keyFile)) {
    throw new Error('set HUGINN_APPD_TLS_CERT and HUGINN_APPD_TLS_KEY together, or neither');
  }
  let minted = false;
  if (!fs.existsSync(cf) || !fs.existsSync(kf)) {
    if (operatorSupplied) throw new Error(`${!fs.existsSync(cf) ? cf : kf} does not exist`);
    fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
    fs.chmodSync(dir, 0o700);
    const tk = `${kf}.tmp-${process.pid}`;
    const tc = `${cf}.tmp-${process.pid}`;
    try {
      execFileSync('openssl', [
        'req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1',
        '-nodes', '-days', '3650', '-subj', '/CN=huginn-appd',
        '-keyout', tk, '-out', tc,
      ], { stdio: ['ignore', 'ignore', 'pipe'], timeout: 30_000 });
      fs.chmodSync(tk, 0o600);
      fs.renameSync(tk, kf);
      fs.renameSync(tc, cf);
    } catch (e) {
      for (const f of [tk, tc]) { try { fs.unlinkSync(f); } catch { /* none */ } }
      const why = e.code === 'ENOENT' ? 'openssl is not installed' : String(e.stderr || e.message).trim();
      throw new Error(`could not mint a self-signed certificate (${why}) — install openssl, or set HUGINN_APPD_TLS_CERT and HUGINN_APPD_TLS_KEY`);
    }
    minted = true;
  }
  const cert = fs.readFileSync(cf, 'utf8');
  const key = fs.readFileSync(kf, 'utf8');
  // Fail at start, not at the first handshake: a mismatched pair would answer
  // every TLS client with an opaque alert and look like a network fault.
  if (!new crypto.X509Certificate(cert).checkPrivateKey(crypto.createPrivateKey(key))) {
    throw new Error(`${kf} is not the private key for ${cf}`);
  }
  const fp = fingerprintOf(cert);
  if (minted) log(`tls: minted a self-signed certificate ${cf} (sha256 ${fp})`);
  return { cert, key, fp, certFile: cf, keyFile: kf, minted };
}

/** True for a connection from this host itself. */
function isLoopback(addr) {
  const a = String(addr || '');
  return a === '::1' || a.startsWith('127.') || a.startsWith('::ffff:127.');
}

/**
 * Who may speak plain HTTP at all.
 *
 *   local (default) — loopback only. Hooks, the CLI over ssh and anything else
 *                     on this host keep working; nothing off-host can send the
 *                     token in the clear and have it honoured.
 *   any             — the pre-3.11 behaviour, for a migration window while old
 *                     clients update. Logged at every start.
 */
function plaintextPolicy(env) {
  const v = String(env.HUGINN_APPD_PLAINTEXT || 'local').trim().toLowerCase();
  if (v === 'any' || v === 'local') return v;
  throw new Error(`HUGINN_APPD_PLAINTEXT must be "local" or "any", not "${v}"`);
}

/**
 * One port, both protocols. A TLS connection's first byte is always 0x16 (a
 * handshake record); no HTTP request line can start with it. So the listener
 * reads the first chunk, puts it back, and hands the socket to whichever server
 * speaks that protocol — clients move to https:// on the address they already
 * have, with no second port to open in anybody's firewall.
 *
 * `httpServer` and `tlsServer` are never `listen()`ed themselves; they only
 * receive sockets. A connection that sends nothing within `idleMs` is dropped
 * before either sees it; once it has spoken, the servers' own headersTimeout
 * and requestTimeout apply, armed when this listener starts listening.
 */
function createSniffingListener(net, httpServer, tlsServer, { idleMs = 10_000 } = {}) {
  const listener = net.createServer((socket) => {
    // ⚠ NAGLE OFF, AS NODE'S OWN HTTP SERVERS DO. http/https servers that
    // listen() themselves turn it off on every socket they accept (their noDelay
    // option, default true); these two never listen, and a plain net server
    // leaves it on. A TLS 1.3 handshake's second write then waited for the
    // client's delayed ACK, ~40 ms per new connection on Linux, and a response
    // written in pieces could wait the same way.
    socket.setNoDelay(true);
    const onTimeout = () => socket.destroy();
    const onError = () => socket.destroy();
    socket.setTimeout(idleMs, onTimeout);
    socket.on('error', onError);
    socket.once('data', (chunk) => {
      socket.pause();
      socket.setTimeout(0);
      socket.removeListener('timeout', onTimeout);
      socket.removeListener('error', onError);
      socket.unshift(chunk);
      (chunk[0] === 0x16 ? tlsServer : httpServer).emit('connection', socket);
      process.nextTick(() => socket.resume());
    });
  });
  // ⚠ NODE STARTS ITS REQUEST TIMEOUTS ON A SERVER'S OWN 'listening', AND THESE
  // TWO NEVER LISTEN. headersTimeout and requestTimeout are enforced by a timer
  // each http/https server sets up when it emits 'listening'. A server that only
  // ever gets sockets through emit('connection') never does, so the idle timer
  // above was the only bound: one byte — or a finished TLS handshake — cleared
  // it, and the connection was then held for as long as the peer liked, before
  // any token check. The servers are listening when this listener is, so they
  // hear it from here.
  listener.on('listening', () => {
    httpServer.emit('listening');
    tlsServer.emit('listening');
  });
  return listener;
}

module.exports = {
  BOUND_PREFIX, fingerprintOf, boundProof, loadOrCreate, isLoopback, plaintextPolicy, createSniffingListener,
};
