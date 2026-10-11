'use strict';
// The last completed model switch of a session, as hooks/huginn-modelswitch-hook
// recorded it from Claude Code's PostModelSwitch event. The headroom ladder reads
// it as structured proof that a move landed — the pane's `… for this session
// only` line stays the other proof, so a host without the hook behaves as before.

const fs = require('fs');
const path = require('path');

const DIRNAME = '.modelswitch';

function switchFile(stateDir, name) { return path.join(stateDir, DIRNAME, name); }

/** {sessionId, from, to, requested, source, ts} or null. Never throws. */
function readSwitch(stateDir, name) {
  try {
    const o = JSON.parse(fs.readFileSync(switchFile(stateDir, name), 'utf8'));
    if (!o || o.v !== 1 || typeof o.to !== 'string' || !Number.isFinite(o.ts)) return null;
    return o;
  } catch { return null; }
}

function clearSwitch(stateDir, name) {
  try { fs.unlinkSync(switchFile(stateDir, name)); } catch { /* gone */ }
}

module.exports = { DIRNAME, switchFile, readSwitch, clearSwitch };
