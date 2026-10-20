'use strict';
// A Round's run transcripts never outlive the history that points at them, when
// skipped fires are mixed in with real runs.
//
// Round chats are hidden from /v1/chats, so a run's row in `runs` is the only way
// to reach its transcript, and the row going is the transcript going (finishRoundRun
// prunes what falls off the end). recordSkippedRound wrote the same history without
// the prune: once it was full, every skip pushed the oldest REAL run out and left
// its transcript on disk with nothing pointing at it, for good.
//
// The daemon's own round store, finishRoundRun and recordSkippedRound, lifted into a
// sandbox over a scratch data directory.

const { test, before, after } = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const roundsLib = require('../lib/rounds');
const { lift } = require('./appd-lift');

let tmp, ROUNDS_DIR, CHATS_DIR, appd;
const activeRuns = new Map();

before(() => {
  tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'appd-round-prune-'));
  ROUNDS_DIR = path.join(tmp, 'rounds'); CHATS_DIR = path.join(tmp, 'chats');
  fs.mkdirSync(ROUNDS_DIR); fs.mkdirSync(CHATS_DIR);
  appd = lift(['roundPath', 'loadRound', 'saveRound', 'updateRound', 'pushRoundRun',
    'finishRoundRun', 'recordSkippedRound', 'chatDir'], {
    fs, path, log: () => {}, roundsLib, activeRuns, ROUNDS_DIR, CHATS_DIR,
    clampRoundTimeout: (s) => s || 900,
    drainPending: () => [], saveMeta: () => {}, appendMsg: () => {},
    loadMsgs: () => [], updateMeta: () => {},
    deliverRoundReport: async () => {},
  }, { constants: ['UUID_RE', 'MAX_RUN_HISTORY'] }).fns;
});

after(() => { if (tmp) fs.rmSync(tmp, { recursive: true, force: true }); });

function newRound() {
  const id = crypto.randomUUID();
  appd.saveRound({ id, title: 'nightly check', notifyWhen: 'never', runs: [], schedule: { kind: 'daily', at: '03:00', tz: 'UTC' } });
  return id;
}

/** A real run: a transcript on disk, then finished the way settleRun finishes it. */
function realRun(roundId) {
  const chatId = crypto.randomUUID();
  fs.mkdirSync(path.join(CHATS_DIR, chatId));
  fs.writeFileSync(path.join(CHATS_DIR, chatId, 'messages.jsonl'), '');
  appd.finishRoundRun({ id: chatId, roundId, roundStartedAt: Math.floor(Date.now() / 1000) }, 'claude exited 1');
  return chatId;
}

/** Transcripts on disk that no row of the Round's history points at. */
function orphans(roundId) {
  const referenced = new Set((appd.loadRound(roundId).runs || []).map((r) => r.chatId).filter(Boolean));
  return fs.readdirSync(CHATS_DIR).filter((id) => !referenced.has(id));
}

test('a skip that pushes a real run out of a full history deletes its transcript', () => {
  const id = newRound();
  const runs = [];
  for (let i = 0; i < 10; i++) runs.push(realRun(id));
  assert.deepEqual(orphans(id), [], 'the control: ten real runs leave nothing behind');

  appd.recordSkippedRound(id, 'LAPTOP has not checked in');
  const round = appd.loadRound(id);
  assert.equal(round.runs.length, 10);
  assert.equal(round.runs[0].skipped, true);
  assert.equal(fs.existsSync(path.join(CHATS_DIR, runs[0])), false, 'the oldest run fell off the history and its transcript went with it');
  assert.deepEqual(orphans(id), []);
  for (const chatId of runs.slice(1)) assert.ok(fs.existsSync(path.join(CHATS_DIR, chatId)), 'the runs still listed keep theirs');
  fs.rmSync(CHATS_DIR, { recursive: true, force: true }); fs.mkdirSync(CHATS_DIR);
});

test('a year of a daily Round on a laptop asleep two nights a week leaves no orphan', () => {
  const id = newRound();
  for (let day = 0; day < 365; day++) {
    if (day % 7 === 2 || day % 7 === 5) appd.recordSkippedRound(id, 'LAPTOP is asleep');
    else realRun(id);
  }
  assert.deepEqual(orphans(id), []);
  const referenced = appd.loadRound(id).runs.filter((r) => r.chatId).length;
  assert.equal(fs.readdirSync(CHATS_DIR).length, referenced, 'exactly the transcripts the history points at');
  fs.rmSync(CHATS_DIR, { recursive: true, force: true }); fs.mkdirSync(CHATS_DIR);
});

test('a run still going is never pruned, even when a skip pushes it out', () => {
  const id = newRound();
  const runs = [];
  for (let i = 0; i < 10; i++) runs.push(realRun(id));
  activeRuns.set(runs[0], {});
  try {
    appd.recordSkippedRound(id, 'previous run is still going');
    assert.ok(fs.existsSync(path.join(CHATS_DIR, runs[0])), 'a live chat is left alone');
  } finally {
    activeRuns.delete(runs[0]);
  }
  fs.rmSync(CHATS_DIR, { recursive: true, force: true }); fs.mkdirSync(CHATS_DIR);
});
