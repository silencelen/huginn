'use strict';
// The auto-switcher and a saved login the switch gate will refuse.
//
// A login nobody has used for weeks prices as 0% everywhere (`agedLimits` zeroes
// every window that has rolled over), so a DEAD one looks like the freshest
// account on the host. The arbiter used to pick it, have the gate refuse it,
// and pick it again the next minute with nothing changed: a healthy account
// was never switched to, the model ladder sat behind "one lever per tick", and a
// login whose refresh kept failing was posted on every pass. These tests pin
// that such a login is passed over before the pick, and that `why` still names
// it when it is the reason nothing switched.
//
// Pure: headroom.decide and oauth-refresh.switchRefusal, no daemon, no network.

const { test } = require('node:test');
const assert = require('node:assert');
const headroom = require('../lib/headroom');
const { agedLimits } = require('../lib/autoswitch');
const { switchRefusal, unrefreshableClause } = require('../lib/oauth-refresh');

const NOW = Date.parse('2026-10-19T03:00:00Z');
const HOUR = 3600_000;
const DAY = 24 * HOUR;

const snap = (pct, resetsAt) => ({
  at: 0,
  limits: [
    { kind: 'session', percent: pct, severity: 'normal', resetsAt, label: 'Current session' },
    { kind: 'weekly_all', percent: pct, severity: 'normal', resetsAt, label: 'Current week, all models' },
    { kind: 'weekly_scoped', percent: pct, severity: 'normal', resetsAt, label: 'Current week (Fable)' },
  ],
});
const live = (pct) => headroom.windowsOf(snap(pct, new Date(NOW + 2 * HOUR).toISOString()).limits);
/** Read two weeks ago at 100%: every window has since rolled over, so it ages to 0%. */
const agedOut = () => headroom.windowsOf(agedLimits(snap(100, new Date(NOW - 13 * DAY).toISOString()), NOW));

const DEAD_WHY = 'cannot be switched to: its login expired on 2026-10-12 — sign in again';
const settings = { ...headroom.defaults(), accountSwitch: { enabled: true, threshold: 95, margin: 20 } };
const fableSession = { claudeSessionId: 's1', name: 'jotter', state: 'running', model: 'claude-fable-5-1', family: 'fable' };

function verdict({ candidates, sessions = [] }) {
  return headroom.decide({
    active: { slug: 'A', email: 'a@x', windows: live(96) },
    candidates,
    sessions,
    settings,
    state: { lastSwitchAt: 0, lastLadderAt: 0 },
    now: NOW,
  });
}
const switchTo = (v) => (v.actions.find((a) => a.type === 'switch_account') || {}).slug ?? null;

test('a dead login that prices as the freshest is passed over for a healthy one', () => {
  const v = verdict({
    candidates: [
      { slug: 'DEAD', email: 'dead@x', windows: agedOut(), blocked: DEAD_WHY },
      { slug: 'OK', email: 'ok@x', windows: live(30) },
    ],
  });
  assert.equal(switchTo(v), 'OK', 'the healthy account is the one switched to');
});

test('with only a dead alternative, the model ladder is not held behind a switch that cannot happen', () => {
  const v = verdict({
    candidates: [{ slug: 'DEAD', email: 'dead@x', windows: agedOut(), blocked: DEAD_WHY }],
    sessions: [fableSession],
  });
  assert.equal(switchTo(v), null, 'no switch to a login the gate refuses');
  assert.ok(v.actions.some((a) => a.type === 'ladder_down'),
    `the ladder moves the Fable session instead (actions: ${JSON.stringify(v.actions.map((a) => a.type))})`);
});

test('when the dead login is why nothing switched, `why` says to sign in to it', () => {
  // The other saved account is full, so nothing can be switched to — and the
  // sentence the owner reads names the account they can do something about.
  const v = verdict({
    candidates: [
      { slug: 'DEAD', email: 'dead@x', windows: agedOut(), blocked: DEAD_WHY },
      { slug: 'FULL', email: 'full@x', windows: live(99) },
    ],
  });
  assert.equal(switchTo(v), null);
  assert.equal(v.why, `dead@x ${DEAD_WHY}`);
});

test('the Fable preference never re-picks a passed-over login', () => {
  // Decision 5 re-picks among the candidates with Fable room left. A dead
  // login's aged-out Fable window reads as all room, so the re-pick has to skip
  // it as well, or it wins straight back.
  const noFable = headroom.windowsOf(snap(10, new Date(NOW + 2 * HOUR).toISOString()).limits
    .filter((l) => l.kind !== 'weekly_scoped'));
  const v = verdict({
    candidates: [
      { slug: 'DEAD', email: 'dead@x', windows: agedOut(), blocked: DEAD_WHY },
      { slug: 'OK', email: 'ok@x', windows: noFable },
    ],
  });
  assert.equal(switchTo(v), 'OK');
});

// ---- which saved logins are passed over ----------------------------------------

function rec(over = {}, refresh = null) {
  return {
    email: 'b@x',
    refresh,
    credentials: {
      claudeAiOauth: {
        accessToken: 'at', refreshToken: 'rt', expiresAt: NOW - HOUR, refreshTokenExpiresAt: NOW + 20 * DAY,
        scopes: ['user:inference'], subscriptionType: 'max', ...over,
      },
    },
  };
}

test('a login only a sign-in can bring back is passed over, with the gate`s own sentence', () => {
  const dead = rec({}, { deadAt: NOW - 7 * DAY, lastStatus: 'known_dead_refresh_token' });
  assert.equal(switchRefusal(dead, NOW), unrefreshableClause(dead));
  assert.match(switchRefusal(rec({ refreshTokenExpiresAt: NOW - DAY }), NOW),
    /^cannot be switched to: its login expired on \d{4}-\d{2}-\d{2} — sign in again$/);
});

test('an expired login whose refresh is backing off is passed over until the back-off ends', () => {
  const held = rec({}, { lastStatus: 'account_on_hold', nextAt: NOW + 8 * HOUR });
  assert.match(switchRefusal(held, NOW), /^cannot be switched to before 2026-10-19 11:00 UTC: its last refresh ended in account_on_hold$/);
  assert.equal(switchRefusal(held, NOW + 8 * HOUR + 1), null, 'once nextAt passes it may be tried again');
});

test('a login the gate can simply refresh, or switch to as it is, is not passed over', () => {
  assert.equal(switchRefusal(rec(), NOW), null, 'expired, never tried: the gate refreshes it once');
  assert.equal(switchRefusal(rec({}, { lastStatus: 'refresh_failed', nextAt: NOW - 1 }), NOW), null, 'back-off over');
  assert.equal(switchRefusal(rec({ expiresAt: NOW + 30 * 60_000 }, { lastStatus: 'refreshed', nextAt: NOW + 25 * 60_000 }), NOW), null,
    'expiring after a good refresh: still a login with a working token');
  assert.equal(switchRefusal(rec({ expiresAt: NOW + 5 * HOUR }), NOW), null, 'fresh');
});
