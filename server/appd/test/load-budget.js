'use strict';
// Time budgets for a LOADED host.
//
// The full suite runs ~80 files in parallel, often beside other heavy work
// (Gradle builds, other suites). A test that polls for a condition with a
// budget tuned on a quiet box then fails although nothing is wrong: the
// condition arrives, only later. Several such files failed full runs and
// passed alone every time, which blocked releases for no reason.
//
// Two rules, and the reason they keep every test's meaning:
//
// 1. A WAIT budget ("poll until X, give up after N ms") is scaled by
//    `budget()`. A wait returns the moment its condition holds, so a larger
//    budget costs nothing on a quiet host and loosens nothing: it only moves
//    the point at which a test gives up on a condition that IS coming.
//    Never use it for a wait whose point is that something does NOT happen —
//    there the length of the window is the assertion.
//
// 2. A LATENCY bound ("not slowed", "at once") is never scaled. It is
//    asserted against a baseline measured in the same test under the same
//    load (`relativeBound`), so it keeps proving "this path adds no wait"
//    rather than "this machine was idle".
//
// HUGINN_TEST_TIME_SCALE overrides the factor (1 reproduces the old budgets).

const SCALE = (() => {
  const v = Number(process.env.HUGINN_TEST_TIME_SCALE);
  return Number.isFinite(v) && v > 0 ? v : 3;
})();

/** A wait-for-condition budget, scaled for load. */
function budget(ms) {
  return Math.round(ms * SCALE);
}

/**
 * The most a step may take when its point is that it adds NO wait of its own:
 * the slower of an absolute floor (what it takes on a quiet host, with margin)
 * and a multiple of a control measured beside it under the same load.
 */
function relativeBound(controlMs, { floorMs, factor = 3 } = {}) {
  return Math.max(floorMs, Math.round(controlMs * factor));
}

/**
 * Keep a fact true for `ms`, re-asserting it every `every` ms.
 *
 * For a daemon decision that must OBSERVE a state before the test moves on, when
 * nothing on the wire says it has. The soft-end machine (lib/softend.js) arms
 * only when one of its passes reads `running`; the passes ride the state-file
 * watch (late under load) and a floor tick every 10 s, so holding the state past
 * one floor tick ({@link SOFT_END_FLOOR_MS}) is what makes "it was seen" certain.
 * Each re-write also fires the watch.
 */
async function holdFor(ms, every, fn) {
  const until = Date.now() + ms;
  fn();
  while (Date.now() < until) {
    await new Promise((r) => setTimeout(r, every));
    fn();
  }
}

/** One soft-end floor tick (huginn-appd.js: setInterval(softEndTick, 10_000)), plus margin. */
const SOFT_END_FLOOR_MS = 10_500;

module.exports = { SCALE, budget, relativeBound, holdFor, SOFT_END_FLOOR_MS };
