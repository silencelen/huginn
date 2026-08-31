'use strict';
const test = require('node:test');
const assert = require('node:assert');
const crypto = require('node:crypto');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const { FcmSender, trySender } = require('../lib/fcm');
const { ServiceAccount, buildAssertion } = require('../lib/gtoken');

// A throwaway key: these tests must never depend on the real credential, and signing
// has to be exercised for real because a malformed assertion is rejected by Google
// with a message that says nothing useful about which field was wrong.
function fakeKey() {
  const { privateKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  return {
    type: 'service_account',
    project_id: 'test-project',
    client_email: 'sender@test-project.iam.gserviceaccount.com',
    private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }).toString(),
    token_uri: 'https://oauth2.googleapis.com/token',
  };
}

function writeKey(key = fakeKey()) {
  const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'fcm-test-')), 'sa.json');
  fs.writeFileSync(file, JSON.stringify(key));
  return file;
}

/** Records what was sent and replies with whatever the test asks for. */
function stubFetch(responses) {
  const calls = [];
  const queue = [...responses];
  const impl = async (url, opts) => {
    calls.push({ url: String(url), opts });
    const next = queue.shift() ?? { status: 200, body: '{}' };
    return {
      ok: next.status >= 200 && next.status < 300,
      status: next.status,
      text: async () => next.body,
    };
  };
  impl.calls = calls;
  return impl;
}

const TOKEN_OK = { status: 200, body: JSON.stringify({ access_token: 'ya29.test', expires_in: 3600 }) };

test('the signed assertion has three parts and the expected claims', () => {
  const key = fakeKey();
  const jwt = buildAssertion(key, 'scope-a', 1_700_000_000);
  const parts = jwt.split('.');
  assert.equal(parts.length, 3);
  const claims = JSON.parse(Buffer.from(parts[1], 'base64url').toString());
  assert.equal(claims.iss, key.client_email);
  assert.equal(claims.scope, 'scope-a');
  assert.equal(claims.exp - claims.iat, 3600);
});

test('an access token is cached rather than re-exchanged for every send', async () => {
  const sa = new ServiceAccount(writeKey());
  const f = stubFetch([TOKEN_OK, TOKEN_OK]);
  const a = await sa.accessToken(f);
  const b = await sa.accessToken(f);
  assert.equal(a, b);
  assert.equal(f.calls.length, 1, 'one exchange, not two');
});

test('a token near expiry is refreshed before it is handed out', async () => {
  const sa = new ServiceAccount(writeKey());
  const f = stubFetch([
    { status: 200, body: JSON.stringify({ access_token: 'first', expires_in: 30 }) },
    { status: 200, body: JSON.stringify({ access_token: 'second', expires_in: 3600 }) },
  ]);
  assert.equal(await sa.accessToken(f), 'first');
  // Still inside expires_in, but inside the safety margin too: a token handed out
  // with seconds left fails on arrival and looks like a permissions fault.
  assert.equal(await sa.accessToken(f), 'second');
});

test('a rejected token exchange fails loudly rather than silently', async () => {
  const sa = new ServiceAccount(writeKey());
  const f = stubFetch([{ status: 400, body: '{"error":"invalid_grant"}' }]);
  await assert.rejects(() => sa.accessToken(f), /token exchange failed \(400\)/);
});

test('a key file that is not a service account is refused at construction', () => {
  const file = writeKey({ project_id: 'x' });
  assert.throws(() => new ServiceAccount(file), /not a service-account key/);
});

test('a send posts a high-priority data-only message to the right project', async () => {
  const sender = new FcmSender(writeKey());
  const f = stubFetch([TOKEN_OK, { status: 200, body: '{"name":"projects/x/messages/1"}' }]);
  const r = await sender.send('device-tok', { title: 'T', text: 'B', kind: 'k', subject: 's' }, f);
  assert.equal(r.ok, true);

  const send = f.calls[1];
  assert.match(send.url, /projects\/test-project\/messages:send$/);
  const msg = JSON.parse(send.opts.body).message;
  assert.equal(msg.token, 'device-tok');
  assert.equal(msg.android.priority, 'high', 'normal priority is batched until the device wakes');
  assert.equal(msg.notification, undefined, 'data-only, so the app decides what you have seen');
  assert.deepEqual(msg.data, {
    title: 'T', text: 'B', kind: 'k', subject: 's',
    // Empty when there is nothing to answer, present when there is.
    options: '', fingerprint: '',
  });
});

// What lets a notification offer "1) Yes  2) No" as buttons on a lock screen.
test('a question travels with its options and fingerprint', async () => {
  const sender = new FcmSender(writeKey());
  const f = stubFetch([TOKEN_OK, { status: 200, body: '{}' }]);
  await sender.send('t', {
    title: 'jtyper needs you',
    text: 'Do you want to create jtyper.md?',
    kind: 'session_attention',
    subject: 'jtyper',
    options: [{ number: 1, label: 'Yes' }, { number: 2, label: 'No' }],
    fingerprint: 'abc123def456',
  }, f);
  const data = JSON.parse(f.calls[1].opts.body).message.data;
  assert.equal(data.fingerprint, 'abc123def456');
  assert.deepEqual(JSON.parse(data.options), [
    { number: 1, label: 'Yes' }, { number: 2, label: 'No' },
  ]);
  assert.equal(typeof data.options, 'string', 'FCM data is string-to-string');
});

test('an alert with nothing to answer sends no empty option array', async () => {
  const sender = new FcmSender(writeKey());
  const f = stubFetch([TOKEN_OK, { status: 200, body: '{}' }]);
  await sender.send('t', { title: 'Chat finished', text: 'done', options: [] }, f);
  assert.equal(JSON.parse(f.calls[1].opts.body).message.data.options, '');
});

test('every data value is a string, since FCM rejects anything else', async () => {
  const sender = new FcmSender(writeKey());
  const f = stubFetch([TOKEN_OK, { status: 200, body: '{}' }]);
  await sender.send('t', { title: 'T', text: undefined, kind: null, subject: 7 }, f);
  const data = JSON.parse(f.calls[1].opts.body).message.data;
  for (const [k, v] of Object.entries(data)) {
    assert.equal(typeof v, 'string', `${k} must be a string`);
  }
});

// The distinction the whole retry policy rests on.
test('an unregistered device is reported as dead so it can be forgotten', async () => {
  const sender = new FcmSender(writeKey());
  const f = stubFetch([TOKEN_OK, {
    status: 404,
    body: JSON.stringify({
      error: {
        code: 404, status: 'NOT_FOUND', message: 'Requested entity was not found.',
        details: [{ '@type': 'type.googleapis.com/google.firebase.fcm.v1.FcmError', errorCode: 'UNREGISTERED' }],
      },
    }),
  }]);
  const r = await sender.send('stale', { title: 'T', text: 'B' }, f);
  assert.equal(r.ok, false);
  assert.equal(r.dead, true);
});

test('a server-side outage is NOT treated as a dead token', async () => {
  const sender = new FcmSender(writeKey());
  const f = stubFetch([TOKEN_OK, {
    status: 503,
    body: JSON.stringify({ error: { code: 503, status: 'UNAVAILABLE', message: 'try again' } }),
  }]);
  const r = await sender.send('good-token', { title: 'T', text: 'B' }, f);
  assert.equal(r.ok, false);
  assert.equal(r.dead, false, 'discarding this token would unregister a working phone');
  assert.equal(r.status, 503);
});

test('a 404 that is NOT a dead-token code does not unregister the fleet', async () => {
  // A project-level fault (wrong project id, endpoint gone) can 404 without the
  // token being gone. Deciding "dead" from the bare status would drop every token
  // in that tick; the verdict must come from the error CODE, not the HTTP 404.
  const sender = new FcmSender(writeKey());
  const f = stubFetch([TOKEN_OK, {
    status: 404,
    body: JSON.stringify({ error: { code: 404, status: 'FAILED_PRECONDITION', message: 'project not found' } }),
  }]);
  const r = await sender.send('good-token', { title: 'T', text: 'B' }, f);
  assert.equal(r.ok, false);
  assert.equal(r.dead, false, 'a non-UNREGISTERED 404 must not retire a live token');
  assert.equal(r.status, 404);
});

test('an unparseable error body still yields a usable verdict', async () => {
  const sender = new FcmSender(writeKey());
  const f = stubFetch([TOKEN_OK, { status: 500, body: '<html>gateway</html>' }]);
  const r = await sender.send('t', { title: 'T', text: 'B' }, f);
  assert.equal(r.ok, false);
  assert.equal(r.dead, false);
  assert.match(r.error, /gateway/);
});

// Push being unconfigured is an ordinary state, not a startup failure: Telegram and
// the app's own alarm both work without it.
test('a missing key yields no sender rather than throwing', () => {
  const lines = [];
  assert.equal(trySender('/nonexistent/key.json', (m) => lines.push(m)), null);
  assert.match(lines.join(' '), /not configured/);
});

test('a present key yields a sender that knows its project', () => {
  const sender = trySender(writeKey(), () => { });
  assert.ok(sender);
  assert.equal(sender.projectId, 'test-project');
});

// ------------------------------------------------ asking without delivering
//
// How an uninstall is discovered on a host that rarely alerts. The phone cannot
// report that it is gone and its check-ins just stop, so FCM is the only party
// that knows — and a probe is the only way to ask it without waking every phone.

// ⚠ THE LOAD-BEARING ASSERTION IN THIS FILE. `validate_only` is a sibling of
// `message`, not a field inside it. Nested, it is an unknown field on Message
// and FCM DELIVERS: every daily sweep would fire a real high-priority push at
// every registered phone, which on the one transport built to wake sleeping
// devices is a silent 3am notification storm.
test('a probe sets validate_only beside the message, never inside it', async () => {
  const sender = new FcmSender(writeKey());
  const f = stubFetch([TOKEN_OK, { status: 200, body: '{"name":"projects/x/messages/fake"}' }]);
  const r = await sender.validate('device-tok', f);
  assert.equal(r.ok, true);

  const body = JSON.parse(f.calls[1].opts.body);
  assert.equal(body.validate_only, true, 'top level, beside message');
  assert.equal(body.message.validate_only, undefined, 'inside the message it would DELIVER');
  assert.equal(body.message.token, 'device-tok');
  assert.match(f.calls[1].url, /projects\/test-project\/messages:send$/);
});

// FCM answers INVALID_ARGUMENT for a malformed PAYLOAD as readily as for a bad
// token, which is why INVALID_ARGUMENT is not a dead-token code. A probe that
// sent junk could therefore not tell the two apart at all.
test('a probe carries a valid payload so the verdict is about the token', async () => {
  const sender = new FcmSender(writeKey());
  const f = stubFetch([TOKEN_OK, { status: 200, body: '{}' }]);
  await sender.validate('t', f);
  const msg = JSON.parse(f.calls[1].opts.body).message;
  assert.equal(msg.notification, undefined, 'data-only, like every other send here');
  for (const [k, v] of Object.entries(msg.data)) {
    assert.equal(typeof v, 'string', `${k} must be a string`);
  }
});

test('a probe reports an unregistered install as dead, exactly as a send does', async () => {
  const sender = new FcmSender(writeKey());
  const f = stubFetch([TOKEN_OK, {
    status: 404,
    body: JSON.stringify({
      error: {
        code: 404, status: 'NOT_FOUND', message: 'Requested entity was not found.',
        details: [{ '@type': 'type.googleapis.com/google.firebase.fcm.v1.FcmError', errorCode: 'UNREGISTERED' }],
      },
    }),
  }]);
  const r = await sender.validate('stale', f);
  assert.equal(r.dead, true);
  assert.equal(r.ok, false);
  assert.equal(r.status, 404);
});

// The sweep runs unattended and drops rows. If an outage read as "dead" it would
// unregister every phone in one pass, with no route back but a reinstall.
test('a probe during an outage is not a verdict about any token', async () => {
  const sender = new FcmSender(writeKey());
  const f = stubFetch([TOKEN_OK, {
    status: 503,
    body: JSON.stringify({ error: { code: 503, status: 'UNAVAILABLE', message: 'try again' } }),
  }]);
  const r = await sender.validate('good-token', f);
  assert.equal(r.dead, false, 'discarding this token would unregister a working phone');
  assert.equal(r.ok, false);
});

// One verdict function, so a probe and a send cannot come to differ about what
// "dead" means — the whole retry policy rests on that one distinction.
test('a probe and a send give the same verdict for the same reply', async () => {
  const reply = {
    status: 400,
    body: JSON.stringify({
      error: {
        code: 400, status: 'INVALID_ARGUMENT', message: 'bad payload',
        details: [{ '@type': 'type.googleapis.com/google.firebase.fcm.v1.FcmError', errorCode: 'INVALID_ARGUMENT' }],
      },
    }),
  };
  const sent = await new FcmSender(writeKey()).send('t', { title: 'T', text: 'B' }, stubFetch([TOKEN_OK, reply]));
  const probed = await new FcmSender(writeKey()).validate('t', stubFetch([TOKEN_OK, reply]));
  assert.deepEqual(
    { ok: probed.ok, dead: probed.dead, status: probed.status },
    { ok: sent.ok, dead: sent.dead, status: sent.status },
  );
  assert.equal(probed.dead, false, 'INVALID_ARGUMENT is a payload verdict, not a token one');
});

// Audit findings, 2026-07-28.

test('INVALID_ARGUMENT does NOT mark a token dead', async () => {
  // FCM returns it for a malformed PAYLOAD as well as a bad token. Treating it
  // as proof of death unregisters every device in one tick over one bad
  // message, after which nothing can push until each phone re-registers.
  const sender = new FcmSender(writeKey());
  const f = stubFetch([TOKEN_OK, {
    status: 400,
    body: JSON.stringify({ error: { status: 'INVALID_ARGUMENT', message: 'bad payload' } }),
  }]);
  const r = await sender.send('device-tok', { title: 'T', text: 'B' }, f);
  assert.equal(r.ok, false);
  assert.equal(r.dead, false, 'INVALID_ARGUMENT must be retryable, not fatal to the registration');
});

test('a genuinely gone registration is still dropped', async () => {
  for (const code of ['UNREGISTERED', 'NOT_FOUND']) {
    const sender = new FcmSender(writeKey());
    const f = stubFetch([TOKEN_OK, {
      status: 404, body: JSON.stringify({ error: { status: code } }),
    }]);
    const r = await sender.send('t', { title: 'T', text: 'B' }, f);
    assert.equal(r.dead, true, code);
  }
});

test('the send is bounded so a hung socket cannot wedge the alert tick', async () => {
  const sender = new FcmSender(writeKey());
  const f = stubFetch([TOKEN_OK, { status: 200, body: '{}' }]);
  await sender.send('t', { title: 'T', text: 'B' }, f);
  assert.ok(f.calls[1].opts.signal, 'no AbortSignal on the FCM request');
  assert.ok(f.calls[0].opts.signal, 'no AbortSignal on the token exchange');
});
