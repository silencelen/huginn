'use strict';
// The one port's listener turns Nagle's algorithm off on every connection it
// accepts, before it hands the connection to the TLS or the plain server.
//
// ⚠ WHY: Node's http/https servers do this themselves when they listen(), and
// the daemon's two never do (lib/tls.js). With Nagle on, a TLS 1.3 handshake's
// second write waited for the client's delayed ACK, ~40 ms per new connection.
//
// SAFETY: no daemon is spawned; the listener binds a port the kernel hands out
// on loopback, and the two "servers" are bare emitters.

const { test } = require('node:test');
const assert = require('node:assert');
const net = require('node:net');
const { EventEmitter } = require('node:events');
const T = require('../lib/tls');

/** `net`, with every accepted socket's setNoDelay recorded into `calls`. */
function spyNet(calls) {
  return {
    createServer(...args) {
      const handler = args.pop();
      return net.createServer(...args, (socket) => {
        const real = socket.setNoDelay.bind(socket);
        socket.setNoDelay = (v) => { calls.push(v === undefined ? true : v); return real(v); };
        handler(socket);
      });
    },
  };
}

for (const [label, firstByte] of [['TLS', 0x16], ['plain HTTP', 0x47]]) {
  test(`a ${label} connection is handed on with Nagle off`, async () => {
    const calls = [];
    const httpServer = new EventEmitter();
    const tlsServer = new EventEmitter();
    const handed = new Promise((resolve) => {
      httpServer.once('connection', (s) => resolve(['http', s]));
      tlsServer.once('connection', (s) => resolve(['tls', s]));
    });
    const listener = T.createSniffingListener(spyNet(calls), httpServer, tlsServer);
    await new Promise((r) => listener.listen(0, '127.0.0.1', r));
    const client = net.connect(listener.address().port, '127.0.0.1');
    try {
      await new Promise((r) => client.once('connect', r));
      client.write(Buffer.from([firstByte]));
      const [to, sock] = await handed;
      assert.strictEqual(to, firstByte === 0x16 ? 'tls' : 'http');
      assert.deepStrictEqual(calls, [true], 'Nagle is turned off once, before the hand-off');
      sock.destroy();
    } finally {
      client.destroy();
      await new Promise((r) => listener.close(r));
    }
  });
}
