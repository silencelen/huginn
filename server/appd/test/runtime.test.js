'use strict';
// The runtime generation and the pure half of cycling a session onto it
// (lib/runtime.js, 2026-11-07): what is hashed and what is not, which process
// in a pane is Claude, how its argv is replayed with `--resume`, what holds a
// restart, and which stale session the sweep takes next.

const { test } = require('node:test');
const assert = require('node:assert');
const rt = require('../lib/runtime');

const SETTINGS = JSON.stringify({
  hooks: { Stop: [{ hooks: [{ type: 'command', command: '/opt/huginn-appd/hooks/x' }] }] },
  statusLine: { type: 'command', command: 'huginn-statusline' },
  fastMode: false,
  env: { A: '1' },
}, null, 2);
const CLAUDE_JSON = JSON.stringify({
  numStartups: 412,
  projects: { '/root': { hasTrustDialogAccepted: true, lastCost: 3.2 } },
  mcpServers: { huginn: { type: 'stdio', command: '/usr/bin/node', args: ['/opt/huginn-appd/mcp/huginn-mcp.js'] } },
  oauthAccount: { emailAddress: 'alex@example.net' },
});
const BIN = { realPath: '/usr/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe', size: 41_000_000, mtimeMs: 1_762_000_000_000 };
const INPUTS = {
  claude: rt.claudeFingerprint(BIN),
  settings: rt.settingsDigest(SETTINGS),
  mcp: rt.mcpDigest(CLAUDE_JSON),
  appd: '3.19.0',
};

test('the generation is a short hash that is the same for the same inputs', () => {
  const a = rt.genOf(INPUTS);
  assert.match(a, /^[0-9a-f]{12}$/);
  assert.strictEqual(rt.genOf({ ...INPUTS }), a);
});

test('the generation changes when ANY input changes: the binary, the settings, the MCP registrations, the appd version', () => {
  const base = rt.genOf(INPUTS);
  const newer = { ...BIN, mtimeMs: BIN.mtimeMs + 1000, size: BIN.size + 7 };
  assert.notStrictEqual(rt.genOf({ ...INPUTS, claude: rt.claudeFingerprint(newer) }), base, 'a replaced binary');
  const hooked = JSON.parse(SETTINGS);
  hooked.hooks.PostModelSwitch = [{ hooks: [{ type: 'command', command: '/opt/huginn-appd/hooks/y' }] }];
  assert.notStrictEqual(rt.genOf({ ...INPUTS, settings: rt.settingsDigest(JSON.stringify(hooked)) }), base, 'a new hook');
  const cj = JSON.parse(CLAUDE_JSON);
  cj.mcpServers.mempalace = { type: 'stdio', command: 'mempalace' };
  assert.notStrictEqual(rt.genOf({ ...INPUTS, mcp: rt.mcpDigest(JSON.stringify(cj)) }), base, 'a new MCP server');
  assert.notStrictEqual(rt.genOf({ ...INPUTS, appd: '3.19.1' }), base, 'a new appd');
});

test('the generation does NOT change for what a session does not load at start', () => {
  const base = rt.genOf(INPUTS);
  // `fastMode` is the one settings key the daemon itself writes, per toggle.
  const fast = JSON.parse(SETTINGS);
  fast.fastMode = true;
  assert.strictEqual(rt.genOf({ ...INPUTS, settings: rt.settingsDigest(JSON.stringify(fast)) }), base, 'fastMode');
  // The same settings re-serialised with keys in another order.
  const reordered = JSON.stringify({ env: { A: '1' }, statusLine: JSON.parse(SETTINGS).statusLine, hooks: JSON.parse(SETTINGS).hooks });
  assert.strictEqual(rt.genOf({ ...INPUTS, settings: rt.settingsDigest(reordered) }), base, 'key order');
  // Everything in ~/.claude.json but mcpServers: project entries, counters, the identity block.
  const cj = JSON.parse(CLAUDE_JSON);
  cj.numStartups += 1;
  cj.projects['/root'].lastCost = 9.9;
  cj.projects['/tmp'] = { hasTrustDialogAccepted: true };
  cj.oauthAccount.emailAddress = 'sam@example.net';
  assert.strictEqual(rt.genOf({ ...INPUTS, mcp: rt.mcpDigest(JSON.stringify(cj)) }), base, 'the rest of ~/.claude.json');
});

test('a torn read of ~/.claude.json answers null, never a new digest; a missing file reads none', () => {
  assert.strictEqual(rt.mcpDigest('{"mcpServers": {"hug'), null);
  assert.strictEqual(rt.mcpDigest(null), 'none');
  assert.strictEqual(rt.settingsDigest(null), 'none');
  // Settings that are not JSON hash as text: a broken file is still a state.
  assert.match(rt.settingsDigest('not json'), /^[0-9a-f]{12}$/);
});

test('the Claude Code version is read from the install beside the binary, or from a versions/ path', () => {
  const files = {
    '/usr/lib/node_modules/@anthropic-ai/claude-code/package.json': JSON.stringify({ name: '@anthropic-ai/claude-code', version: '2.1.316' }),
  };
  const read = (p) => { if (!(p in files)) throw new Error('ENOENT'); return files[p]; };
  assert.strictEqual(rt.claudeVersionFromPath(BIN.realPath, read), '2.1.316');
  assert.strictEqual(rt.claudeVersionFromPath('/home/alex/.local/share/claude/versions/2.1.320/claude', read), '2.1.320');
  assert.strictEqual(rt.claudeVersionFromPath('/tmp/x/bin/claude', read), null);
  assert.strictEqual(rt.claudeVersionFromPath('', read), null);
});

test('a session nobody recorded a generation for takes the current one only when born since it began', () => {
  const current = { gen: 'abc123abc123', claude: '2.1.316', since: 1_000_000 };
  assert.deepStrictEqual(rt.assignGen({ createdAt: 1_000_050, current }), { gen: 'abc123abc123', claude: '2.1.316' });
  assert.deepStrictEqual(rt.assignGen({ createdAt: 1_000_000, current }), { gen: 'abc123abc123', claude: '2.1.316' }, 'the same second counts');
  assert.strictEqual(rt.assignGen({ createdAt: 999_999, current }), null, 'born before: launched under something older');
  assert.strictEqual(rt.assignGen({ createdAt: 1_000_050, current: null }), null, 'no generation yet');
  assert.strictEqual(rt.assignGen({ createdAt: null, current }), null);
});

test('the runtime row field: stale when the recorded generation is not the current one, and when there is none', () => {
  const current = { gen: 'cur000000000', claude: '2.1.316' };
  assert.deepStrictEqual(rt.runtimeFor({ recorded: { gen: 'cur000000000', claude: '2.1.316' }, current }), {
    stale: false, gen: 'cur000000000', current: 'cur000000000', claude: '2.1.316', currentClaude: '2.1.316', restarting: false,
  });
  const old = rt.runtimeFor({ recorded: { gen: 'old000000000', claude: '2.1.306' }, current, restarting: true });
  assert.strictEqual(old.stale, true);
  assert.strictEqual(old.gen, 'old000000000');
  assert.strictEqual(old.claude, '2.1.306');
  assert.strictEqual(old.restarting, true);
  // Launched before the daemon recorded generations: stale once.
  assert.strictEqual(rt.runtimeFor({ recorded: null, current }).stale, true);
  // No current generation yet: nothing is called stale on no evidence.
  const unknown = rt.runtimeFor({ recorded: null, current: null });
  assert.strictEqual(unknown.stale, false);
  assert.strictEqual(unknown.current, null);
});

test('the safe point: the first hold in the order of harm, and force skips only attached', () => {
  assert.strictEqual(rt.safePointHold({}), null);
  assert.strictEqual(rt.safePointHold({ state: 'running', dialog: true, draft: true, bg: true, attached: true }), 'running');
  assert.strictEqual(rt.safePointHold({ state: 'idle', dialog: true, draft: true }), 'dialog');
  assert.strictEqual(rt.safePointHold({ state: 'attention' }), 'dialog', 'a waiting question is a dialog');
  assert.strictEqual(rt.safePointHold({ state: 'idle', draft: true, bg: true }), 'draft');
  assert.strictEqual(rt.safePointHold({ state: 'idle', bg: true, attached: true }), 'background');
  assert.strictEqual(rt.safePointHold({ state: 'idle', attached: true, lull: false }), 'attached');
  assert.strictEqual(rt.safePointHold({ state: 'idle', attached: true, force: true, lull: true }), null, 'force skips attached');
  assert.strictEqual(rt.safePointHold({ state: 'idle', attached: true, force: true, lull: false }), 'load', 'and nothing else');
  assert.strictEqual(rt.safePointHold({ state: 'idle', bg: true, force: true }), 'background');
  assert.strictEqual(rt.safePointHold({ state: 'idle', lull: false }), 'load');
  assert.strictEqual(rt.safePointHold({ state: 'idle', lull: null }), null, 'no PSI reading is no gate');
});

test('the queue\'s words map onto the route\'s', () => {
  assert.strictEqual(rt.holdWord('turn'), 'running');
  assert.strictEqual(rt.holdWord('starting'), 'running');
  assert.strictEqual(rt.holdWord('modal'), 'dialog');
  assert.strictEqual(rt.holdWord('trust'), 'dialog');
  assert.strictEqual(rt.holdWord('attention'), 'dialog');
  assert.strictEqual(rt.holdWord('draft'), 'draft');
  assert.strictEqual(rt.holdWord('background'), 'background');
  assert.strictEqual(rt.holdWord('shell'), null);
  assert.strictEqual(rt.holdWord(null), null);
  assert.deepStrictEqual([...rt.HOLD_WORDS], ['running', 'dialog', 'draft', 'background', 'attached', 'load']);
});

test('the lull is the cuts\' rule: cpu.pressure some avg10 under 5; no PSI is no gate', () => {
  const psi = (v) => `some avg10=${v} avg60=11.59 avg300=20.55 total=101153815999\nfull avg10=0.00 avg60=0.00 avg300=0.00 total=7963794844\n`;
  assert.strictEqual(rt.pressureAvg10(psi('4.56')), 4.56);
  assert.strictEqual(rt.pressureLull(psi('4.99')), true);
  assert.strictEqual(rt.pressureLull(psi('5.00')), false);
  assert.strictEqual(rt.pressureLull(psi('37.10')), false);
  assert.strictEqual(rt.pressureLull('garbage'), true);
  assert.strictEqual(rt.pressureLull(''), true);
  assert.strictEqual(rt.pressureLull(psi('7'), { avg10Max: 10 }), true);
});

test('the Claude process under a pane: by name first, else the first non-shell; shells alone are nobody', () => {
  const procs = {
    10: { argv: ['sh', '-c', 'claude; exec "$SHELL" -l'], comm: 'sh' },
    11: { argv: ['claude', '--name', 'web/lead'], comm: 'claude' },
    20: { argv: ['bash'], comm: 'bash' },
    21: { argv: ['/usr/bin/node', '/usr/lib/node_modules/@anthropic-ai/claude-code/cli.js', '--resume', 'x'], comm: 'node' },
    30: { argv: ['-bash'], comm: 'bash' },
    40: { argv: ['sh', '-c', 'claude …'], comm: 'sh' },
    41: { argv: ['/usr/bin/node', '/tmp/fixtures/fake-claude.js', '--effort', 'high'], comm: 'node' },
    42: { argv: ['sleep', '5'], comm: 'sleep' },
  };
  const readers = { argvOf: (p) => (procs[p] ? procs[p].argv : null), commOf: (p) => (procs[p] ? procs[p].comm : null) };
  assert.strictEqual(rt.pickClaudePid([10, 11], readers), 11, 'the native binary by comm and argv[0]');
  assert.strictEqual(rt.pickClaudePid([20, 21], readers), 21, 'node running cli.js');
  assert.strictEqual(rt.pickClaudePid([30], readers), null, 'a login shell alone: Claude has exited');
  assert.strictEqual(rt.pickClaudePid([40, 41, 42], readers), 41, 'a stand-in: the first thing that is not a shell');
  assert.strictEqual(rt.pickClaudePid([99], readers), null);
  assert.strictEqual(rt.pickClaudePid([], readers), null);
});

test('the flags after the program, for the native binary and for node running a script', () => {
  assert.deepStrictEqual(rt.claudeArgs(['claude', '--name', 'web/lead', '--effort', 'high']), ['--name', 'web/lead', '--effort', 'high']);
  assert.deepStrictEqual(rt.claudeArgs(['/usr/bin/node', '/x/claude-code/cli.js', '--model', 'opus']), ['--model', 'opus']);
  assert.deepStrictEqual(rt.claudeArgs(['node', '--max-old-space-size=4096', '/x/cli.js', '-c']), ['-c']);
  assert.deepStrictEqual(rt.claudeArgs(['claude']), []);
  assert.deepStrictEqual(rt.claudeArgs([]), []);
});

test('the replay puts --resume <id> first and drops whatever resume or continue the session had', () => {
  const id = '11111111-2222-4333-8444-555555555555';
  assert.deepStrictEqual(rt.replayArgs(['--name', 'web/lead', '--effort', 'high'], id),
    ['--resume', id, '--name', 'web/lead', '--effort', 'high']);
  assert.deepStrictEqual(rt.replayArgs(['--resume', 'aaaaaaaa-0000-4000-8000-000000000000', '--name', 'web/core'], id),
    ['--resume', id, '--name', 'web/core']);
  assert.deepStrictEqual(rt.replayArgs(['--resume=old', '--model', 'opus'], id), ['--resume', id, '--model', 'opus']);
  assert.deepStrictEqual(rt.replayArgs(['-r', 'old', '-c', '--continue', '--effort', 'low'], id), ['--resume', id, '--effort', 'low']);
  // `--resume` takes its value optionally: a flag after it is not its value.
  assert.deepStrictEqual(rt.replayArgs(['--resume', '--model', 'opus'], id), ['--resume', id, '--model', 'opus']);
  assert.deepStrictEqual(rt.replayArgs([], id), ['--resume', id]);
  assert.deepStrictEqual(rt.replayArgs(['--resume', 'old'], null), [], 'no id: nothing to resume, and the old one does not ride');
});

test('the relaunch command is `claude <flags>; exec "$SHELL" -l`, quoted for sh', () => {
  const id = '11111111-2222-4333-8444-555555555555';
  assert.strictEqual(rt.relaunchCommand(['--resume', id, '--name', 'web/lead']),
    `claude --resume ${id} --name web/lead; exec "$SHELL" -l`);
  assert.strictEqual(rt.relaunchCommand(['--append-system-prompt', "it's a 'test' $HOME"]),
    `claude --append-system-prompt 'it'\\''s a '\\''test'\\'' $HOME'; exec "$SHELL" -l`);
  assert.strictEqual(rt.shellQuote(''), "''");
  assert.strictEqual(rt.shellQuote('/var/lib/huginn-appd/projects/render/x.lead.md'), '/var/lib/huginn-appd/projects/render/x.lead.md');
  assert.strictEqual(rt.relaunchCommand([]), 'claude; exec "$SHELL" -l');
});

test('the sweep takes the oldest stale, idle, unattached session with a conversation, skipping the given-up and the queued', () => {
  const row = (name, createdAt, extra = {}) => ({
    name, createdAt, state: 'idle', attachedClients: 0, claudeSessionId: `id-${name}`, addressable: true,
    runtime: { stale: true, restarting: false }, ...extra,
  });
  const rows = [
    row('newest', 500),
    row('attached', 100, { attachedClients: 1 }),
    row('working', 110, { state: 'running' }),
    row('current', 120, { runtime: { stale: false, restarting: false } }),
    row('cycling', 130, { runtime: { stale: true, restarting: true } }),
    row('shell', 140, { claudeSessionId: null, state: null }),
    row('gaveup', 150),
    row('oldest-fit', 200),
    row('unaddressable', 90, { addressable: false }),
  ];
  assert.strictEqual(rt.sweepPick(rows, { skip: new Set(['gaveup']) }).name, 'oldest-fit');
  assert.strictEqual(rt.sweepPick(rows, { skip: new Set(['gaveup', 'oldest-fit']) }).name, 'newest');
  assert.strictEqual(rt.sweepPick(rows.filter((r) => r.name !== 'oldest-fit' && r.name !== 'newest'), { skip: new Set(['gaveup']) }), null);
  assert.strictEqual(rt.sweepPick([], {}), null);
  assert.strictEqual(rt.sweepPick(null, {}), null);
});
