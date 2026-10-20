'use strict';
// The mark a NATIVE switch leaves on a session — Claude Code moved it off Fable
// by itself, or appd answered the consent dialog for it — and when that mark
// stops being true.
//
// decide() never moves a marked session ("appd never fights a native switch").
// Only the Undo button cleared the mark, so a session the owner put back on
// Fable with `/model` in the terminal, or one restarted on its Fable default,
// kept it for good: the next red week went straight into the consent dialog the
// 92% move exists to prevent, and `why` named a switch long since undone. The
// mark ends when a record NEWER than it says the session is on Fable — never on
// a stale read, and never on a `/model` that was opened and cancelled.
//
// readSessionModel and noteSessionModel are lifted out of huginn-appd.js and
// run over transcripts on disk, the way the headroom tick runs them.

const { test } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const headroomLib = require('../lib/headroom');
const { readTranscript } = require('../lib/transcript');
const paneLib = require('../lib/pane');
const { lift } = require('./ladder-lift');

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), `ladder-mark-${process.pid}-`));
const DAY = 86_400_000;
const MIN = 60_000;
const T0 = Date.now() - 9 * DAY;                 // the week the switch happened
const iso = (ms) => new Date(ms).toISOString();

const reply = (model, at) => ({ type: 'assistant', message: { model, role: 'assistant', content: [{ type: 'text', text: 'done' }] }, timestamp: iso(at) });
const turnEnd = (at) => ({ type: 'system', subtype: 'turn_duration', durationMs: 900, timestamp: iso(at) });
const caveat = (at) => ({ type: 'user', isMeta: true, message: { role: 'user', content: '<local-command-caveat>Caveat: local command</local-command-caveat>' }, timestamp: iso(at) });
const command = (args, at) => ({ type: 'user', message: { role: 'user', content: `<command-name>/model</command-name>\n            <command-message>model</command-message>\n            <command-args>${args}</command-args>` }, timestamp: iso(at) });
const stdout = (text, at) => ({ type: 'user', message: { role: 'user', content: `<local-command-stdout>${text}</local-command-stdout>` }, timestamp: iso(at) });
const consent = (at) => ({ type: 'system', subtype: 'model_consent_fallback', choice: 'switch', toModel: 'claude-opus-5', timestamp: iso(at) });

let n = 0;
function tick(records, rec, now, settings = headroomLib.defaults()) {
  const name = `s${n++}`;
  const file = path.join(tmp, `${name}.jsonl`);
  fs.writeFileSync(file, `${records.map((r) => JSON.stringify(r)).join('\n')}\n`);
  const logs = [];
  const L = lift(['readSessionModel', 'noteSessionModel'], {
    fs, path, Buffer, readTranscript, headroomLib, paneLib, Date, JSON,
    readSessionState: () => ({ sessionId: `sid-${name}`, transcript: file }),
    log: (...a) => logs.push(a.join(' ')),
    repairDefaultModel: () => ({ ok: true, changed: false }),
  }, { helpers: ['lastModelRecord', 'localCommandOutput', 'LADDER_FEEDBACK_RE', 'transcriptTail'] });
  L.noteSessionModel(rec, L.readSessionModel(name), now, settings);
  return logs;
}

/** What the arbiter does with the session at 95% of the Fable week, nothing else in the way. */
function arbiter(rec, now) {
  return headroomLib.decide({
    active: { slug: 'a', windows: { session: { percent: 5 }, weekly_all: { percent: 30 }, weekly_fable: { percent: 95, label: 'Current week (Fable)' } } },
    settings: headroomLib.defaults(), now, state: { lastLadderAt: 0 },
    sessions: [{ claudeSessionId: 'sid-n', name: 'n', state: 'idle', headsUpAt: now, ...rec }],
  });
}

/** n fell back to opus on its own in week 1; appd marked it and later offered it back. */
function marked(how = 'native') {
  return {
    name: 'n', family: 'opus', model: 'claude-opus-5', ladder: null, humanSetModelAt: null,
    offeredLadderUpAt: T0 + 2 * DAY, nativeSwitch: { seenAt: T0 + MIN, to: 'opus', how },
  };
}
const week1 = [reply('claude-fable-5-1', T0 - MIN), turnEnd(T0 - MIN + 1), reply('claude-opus-5', T0), turnEnd(T0 + 1)];

test('the owner puts the session back on Fable with /model, and the next red week moves it as usual', () => {
  const rec = marked();
  const back = T0 + 4 * DAY;
  tick([...week1, caveat(back), command('fable', back + 1), stdout('Set model to Fable 5.1', back + 2)], rec, back + 2 * MIN);
  assert.equal(rec.family, 'fable');
  assert.equal(rec.nativeSwitch.seenAt, null, 'the switch it recorded has been undone');
  assert.equal(rec.offeredLadderUpAt, null, 'and its "back to Fable?" offer goes with it');

  const v = arbiter(rec, T0 + 8 * DAY);
  assert.deepEqual(v.actions.filter((a) => a.type === 'ladder_down').map((a) => a.name), ['n'], v.why);
  assert.doesNotMatch(v.why, /by Claude Code itself/);
});

test('...and so does one restarted on its Fable default, with no /model at all', () => {
  const rec = marked('appd-consent');
  const restarted = T0 + 5 * DAY;
  tick([...week1, { type: 'user', message: { content: 'carry on' }, origin: { kind: 'human' }, timestamp: iso(restarted) },
    reply('claude-fable-5-1', restarted + 1000), turnEnd(restarted + 2000)], rec, restarted + MIN);
  assert.equal(rec.nativeSwitch.seenAt, null);
});

test('a read that is merely stale does not clear the mark', () => {
  // appd answered the consent dialog a minute ago; the session has not replied
  // since, so the newest record naming a model is still the last Fable reply.
  const rec = { ...marked('appd-consent'), family: 'fable', nativeSwitch: { seenAt: T0 + 10 * MIN, to: 'opus', how: 'appd-consent' } };
  tick([reply('claude-fable-5-1', T0), turnEnd(T0 + 1)], rec, T0 + 11 * MIN);
  assert.equal(rec.nativeSwitch.seenAt, T0 + 10 * MIN, 'still marked');
  assert.equal(arbiter(rec, T0 + 12 * MIN).actions.some((a) => a.type === 'ladder_down'), false);
});

test('a /model the owner opened and cancelled names no model and clears nothing', () => {
  const rec = marked();
  const opened = T0 + 4 * DAY;
  tick([...week1, caveat(opened), command('', opened + 1)], rec, opened + MIN);
  assert.ok(rec.humanSetModelAt > rec.nativeSwitch.seenAt, 'the /model is seen');
  assert.equal(rec.nativeSwitch.seenAt, T0 + MIN, 'but the session is still on opus');
});

test('a consent record already acted on does not re-arm the mark it made after it is cleared', () => {
  const rec = marked('consent');
  const back = T0 + 4 * DAY;
  const records = [reply('claude-fable-5-1', T0 - MIN), consent(T0), reply('claude-opus-5', T0 + 1000),
    caveat(back), command('fable', back + 1), stdout('Set model to Fable 5.1', back + 2)];
  tick(records, rec, back + MIN);
  assert.equal(rec.nativeSwitch.seenAt, null, 'cleared');
  tick(records, rec, back + 2 * MIN);                 // the record is still in the tail
  assert.equal(rec.nativeSwitch.seenAt, null, 'and it stays cleared');

  // A NEW consent switch is a new mark (read before the session has replied on
  // the model it chose, so it is the consent record that says so).
  const again = T0 + 6 * DAY;
  tick([...records, consent(again)], rec, again + MIN);
  assert.equal(rec.nativeSwitch.how, 'consent');
  assert.equal(rec.nativeSwitch.seenAt, again + MIN);
});

test('the next native switch is offered back again', () => {
  const rec = marked();
  const back = T0 + 4 * DAY;
  tick([...week1, caveat(back), command('fable', back + 1), stdout('Set model to Fable 5.1', back + 2)], rec, back + MIN);
  // Week 2: Claude Code moves it off Fable by itself once more.
  const fell = T0 + 13 * DAY;
  tick([...week1, caveat(back), command('fable', back + 1), stdout('Set model to Fable 5.1', back + 2),
    reply('claude-fable-5-1', fell - MIN), reply('claude-opus-5', fell)], rec, fell + MIN);
  assert.equal(rec.nativeSwitch.seenAt, fell + MIN);
  const v = headroomLib.decide({
    active: { slug: 'a', windows: { weekly_fable: { percent: 10 } } }, settings: headroomLib.defaults(),
    now: fell + 2 * DAY, state: {}, lastFableResetAt: fell + DAY,
    sessions: [{ claudeSessionId: 'sid-n', name: 'n', state: 'idle', ...rec }],
  });
  assert.deepEqual(v.actions.filter((a) => a.type === 'offer_ladder_up').map((a) => a.name), ['n']);
});
