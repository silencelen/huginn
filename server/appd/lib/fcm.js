'use strict';
// Sending a push through Firebase Cloud Messaging.
//
// This is the only transport Google delivers into Doze, which is the whole reason it
// is worth the setup: a high-priority message wakes a sleeping phone and is handed a
// brief window of network access. Everything else this daemon can do — an alarm the
// app sets for itself, a Telegram message — either waits for the next beat or arrives
// on a different app entirely.
//
// DATA-ONLY messages, deliberately. Including a `notification` block would let the
// system tray render it with no app code involved, which sounds more robust, but the
// app's own handler is then NOT called while it is backgrounded — so the app could
// never record that it had already told you, and the ten-minute alarm would announce
// the same thing again later. Data-only keeps one place deciding what you have
// already seen. The cost is that a force-stopped app receives nothing, which is
// exactly the case the Telegram fallback exists for.

const { ServiceAccount } = require('./gtoken');

const SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';

/**
 * Errors that mean a stored token is dead rather than that sending is broken.
 *
 * Worth separating, because the two demand opposite responses: a dead token should be
 * forgotten so it stops being retried forever, while a broken sender must NOT cause
 * every token to be discarded — that would quietly unregister a working phone over an
 * outage and leave no way back except reinstalling.
 */
// Codes that mean "this registration is gone; forget it". INVALID_ARGUMENT is
// deliberately NOT here: FCM returns it for a malformed PAYLOAD too, so one bad
// message (a non-string data value, an over-long field) would be read as every
// device being dead and would unregister the whole fleet in a single tick —
// after which nothing can push until each phone happens to re-register.
const DEAD_TOKEN_CODES = new Set(['UNREGISTERED', 'NOT_FOUND']);

/** Long enough for a slow mobile round trip, short enough not to wedge a tick. */
const FCM_TIMEOUT_MS = 15_000;

class FcmSender {
  /** @param keyPath service-account JSON; absent means push is simply not configured. */
  constructor(keyPath) {
    this.sa = new ServiceAccount(keyPath, SCOPE);
    this.projectId = this.sa.projectId;
  }

  get email() { return this.sa.email; }

  /**
   * Sends one alert to one device.
   *
   * @returns {Promise<{ok: boolean, dead: boolean, status: number, error: string|null}>}
   *   `dead` distinguishes "forget this token" from "try again later".
   */
  async send(token, { title, text, kind, subject, options, fingerprint }, fetchImpl = fetch) {
    return this.#post(fetchImpl, {
      message: {
        token,
        // Strings only: FCM rejects a data payload with non-string values, and
        // finding that out at 3am is not the moment.
        data: {
          title: String(title ?? ''),
          text: String(text ?? ''),
          kind: String(kind ?? ''),
          subject: String(subject ?? ''),
          // The question, so the notification can offer its options as buttons
          // and be answered without opening the app. JSON in a string because an
          // FCM data payload is string-to-string and nothing else.
          options: options && options.length ? JSON.stringify(options) : '',
          fingerprint: String(fingerprint ?? ''),
        },
        android: {
          // The point of the exercise. Normal priority is batched until the
          // device next wakes, which is the behaviour being escaped.
          priority: 'high',
          // Dropped rather than delivered late: an alert about a session that
          // wanted an answer an hour ago is noise.
          ttl: '1800s',
        },
      },
    });
  }

  /**
   * Asks FCM whether a token is still real, WITHOUT delivering anything.
   *
   * This is how an uninstall is discovered on a quiet host. Nothing else can:
   * a phone that is gone cannot report that it is gone, and the app's own
   * check-ins simply stop — which is indistinguishable from a phone in a drawer.
   * The only party that knows is FCM, and the only way to ask it is to hand it
   * the token and see what it says. `validate_only` is the documented way to do
   * that: Firebase's own SDKs describe dry-run as "useful for determining
   * whether an FCM registration has been deleted", and the API contract is that
   * a validate-only request fails exactly where the real one would.
   *
   * ⚠ `validate_only` is a SIBLING of `message`, at the top level of the request
   * body — NOT a field inside the message. Nested it is simply an unknown field
   * on Message, and every probe would deliver a real (empty) push to every
   * phone the daemon knows: a silent 3am notification storm on the one path
   * built to wake sleeping devices.
   *
   * The payload is deliberately a known-good minimal data message rather than
   * nothing. FCM answers INVALID_ARGUMENT for a malformed PAYLOAD as well as for
   * a malformed token, so a probe that sent junk could not tell "your token is
   * bad" from "your message is bad" — which is the very reason INVALID_ARGUMENT
   * is not in DEAD_TOKEN_CODES. A fixed valid body keeps the verdict about the
   * token.
   *
   * @returns the same {ok, dead, status, error} verdict shape as [send], so the
   *   caller cannot treat a probe's answer differently from a send's.
   */
  async validate(token, fetchImpl = fetch) {
    return this.#post(fetchImpl, {
      validate_only: true,
      message: {
        token,
        data: { kind: 'probe' },
        android: { priority: 'normal', ttl: '0s' },
      },
    });
  }

  /**
   * One POST to messages:send, and the one place a reply becomes a verdict.
   *
   * Shared so a probe and a real send cannot drift on what "dead" means — the
   * whole retry policy rests on that distinction, and two copies of it is two
   * chances to unregister a working phone over an outage.
   */
  async #post(fetchImpl, body) {
    const accessToken = await this.sa.accessToken(fetchImpl);
    // Bounded, because this runs inside the alert tick and the tick is
    // single-flight: a socket that hangs (captive portal, black-holed route)
    // would otherwise stall EVERY later alert behind it for as long as the OS
    // waits, with no log to say why the phone went quiet.
    const res = await fetchImpl(
      `https://fcm.googleapis.com/v1/projects/${this.projectId}/messages:send`,
      {
        signal: AbortSignal.timeout(FCM_TIMEOUT_MS),
        method: 'POST',
        headers: {
          Authorization: `Bearer ${accessToken}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(body),
      },
    );
    const text = await res.text();
    if (res.ok) return { ok: true, dead: false, status: res.status, error: null };

    let code = null;
    let message = text.slice(0, 200);
    try {
      const parsed = JSON.parse(text);
      message = parsed.error?.message || message;
      code = parsed.error?.details?.find((d) => d.errorCode)?.errorCode
        || parsed.error?.status
        || null;
    } catch { /* keep the raw text */ }

    return {
      ok: false,
      // "Dead" is decided by the error CODE, never the bare HTTP status. The v1
      // send URL embeds the project id, so a 404 can mean "this project/endpoint
      // is wrong", not "this token is gone" — and treating every 404 as dead would
      // unregister the WHOLE fleet in one tick on a project-level fault. A real
      // unregistered token still reports dead: FCM answers UNREGISTERED in the
      // details and NOT_FOUND in error.status, and both are in DEAD_TOKEN_CODES
      // (see the `code` extraction above, which falls back to error.status).
      dead: DEAD_TOKEN_CODES.has(code),
      status: res.status,
      error: message,
    };
  }
}

/**
 * Builds a sender, or null when push is not set up on this host.
 *
 * Returning null rather than throwing because push being unconfigured is an ordinary
 * state — the Telegram path and the app's own alarm both work without it, and the
 * daemon must start regardless.
 */
function trySender(keyPath, log = () => { }) {
  try {
    const sender = new FcmSender(keyPath);
    log(`push: FCM ready for project ${sender.projectId}`);
    return sender;
  } catch (e) {
    log(`push: FCM not configured (${e.message})`);
    return null;
  }
}

module.exports = { FcmSender, trySender, DEAD_TOKEN_CODES, FCM_TIMEOUT_MS, SCOPE };
