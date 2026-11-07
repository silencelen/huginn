'use strict';
/**
 * The runtime GENERATION a Claude Code session was launched under, and the
 * pure half of cycling a session onto the current one (2026-11-07).
 *
 * A Claude Code process loads its MCP servers, its hooks and settings, and its
 * own binary once, at START. A session started before a deploy keeps the old
 * wiring until it is restarted: one session started 10-27 still had no
 * `start_session` tool on 11-07, a tool registered on 11-03. `claude update`
 * is the same story. So the daemon hashes what a session loads at start into a
 * short generation, records it on every session it launches, and a session
 * whose generation is not the current one reads `stale` on its row — and, when
 * `cycleOnDeploy` is on, is quit with `/exit` and relaunched with `--resume` at
 * its own safe point.
 *
 * This module is the decision half — what is hashed, which process in a pane is
 * Claude, how its argv is replayed, what holds a restart, which session the
 * sweep takes next. The daemon owns the fs, /proc and tmux around it, and the
 * unit tests drive these directly.
 */

const { createHash } = require('node:crypto');
const path = require('node:path');

// ------------------------------------------------------------ the generation

/** JSON with every object's keys sorted, so a rewrite that reorders nothing hashes the same. */
function stableStringify(v) {
  if (v === null || typeof v !== 'object') return JSON.stringify(v);
  if (Array.isArray(v)) return `[${v.map(stableStringify).join(',')}]`;
  const keys = Object.keys(v).sort();
  return `{${keys.map((k) => `${JSON.stringify(k)}:${stableStringify(v[k])}`).join(',')}}`;
}

function sha12(text) {
  return createHash('sha256').update(String(text)).digest('hex').slice(0, 12);
}

/**
 * The settings a session reads (`~/.claude/settings.json`), as a digest.
 *
 * ⚠ `fastMode` IS LEFT OUT. The daemon itself writes that one key for
 * `PATCH /v1/settings/claude` (rewriteClaudeSettings), and a toggle from the
 * app is not a reason to call every session on the host stale and cycle them
 * all. Everything else in the file — hooks, the status line, `env`, permissions
 * — is loaded at start and counts. A file that cannot be parsed hashes as its
 * text; no file hashes as `none`.
 */
function settingsDigest(raw) {
  if (raw === null || raw === undefined) return 'none';
  try {
    const o = JSON.parse(raw);
    if (o && typeof o === 'object' && !Array.isArray(o)) {
      const c = { ...o };
      delete c.fastMode;
      return sha12(stableStringify(c));
    }
  } catch { /* not JSON: hash the text */ }
  return sha12(String(raw));
}

/**
 * The user-scope MCP registrations (`mcpServers` in `~/.claude.json`), as a
 * digest — the one thing in that file a session loads at start.
 *
 * ⚠ ONLY `mcpServers`. The file is 115 KB and every running `claude` rewrites it
 * continuously (project entries, the OAuth identity block, counters); hashing
 * the whole of it would move the generation several times an hour. A file
 * caught half-written answers NULL — unreadable, not changed — so the caller
 * keeps its last reading rather than minting a generation off a torn read.
 */
function mcpDigest(raw) {
  if (raw === null || raw === undefined) return 'none';
  try {
    const o = JSON.parse(raw);
    const m = o && typeof o === 'object' && o.mcpServers && typeof o.mcpServers === 'object'
      ? o.mcpServers : {};
    return sha12(stableStringify(m));
  } catch { return null; }
}

/**
 * The `claude` binary's identity: where it really is, how big, when written.
 * It changes exactly when `claude update` (or npm) replaces the binary, which is
 * when the version changes — and it costs a stat, where `claude --version`
 * costs a process. The version STRING rides beside the generation for people
 * to read (claudeVersionFromPath); it is not what is hashed.
 */
function claudeFingerprint({ realPath, size, mtimeMs }) {
  return `${realPath}:${Number(size) || 0}:${Math.floor(Number(mtimeMs) || 0)}`;
}

/**
 * The generation: one short hash over the four things a session loads at start.
 * Each input is a string already (a digest, a fingerprint, the appd version);
 * a missing one reads `none`, so a host without a file still has a generation.
 */
function genOf({ claude, settings, mcp, appd }) {
  const part = (v) => (v === null || v === undefined ? 'none' : String(v));
  return sha12(`${part(claude)}\n${part(settings)}\n${part(mcp)}\n${part(appd)}`);
}

/**
 * Claude Code's version from where its binary lives, without running it: the
 * `package.json` of the npm install the binary sits in, or the `versions/<v>/`
 * directory of the native installer's layout. Null when neither is there (a
 * stand-in on a test PATH), and the caller may ask `claude --version` instead.
 */
function claudeVersionFromPath(realPath, readFile) {
  const p = String(realPath || '');
  if (!p) return null;
  let dir = path.dirname(p);
  for (let i = 0; i < 3; i++) {
    try {
      const pkg = JSON.parse(readFile(path.join(dir, 'package.json')));
      if (pkg && pkg.name === '@anthropic-ai/claude-code' && pkg.version) return String(pkg.version);
    } catch { /* not here */ }
    dir = path.dirname(dir);
  }
  const m = /\/versions\/(\d+\.\d+\.\d+[^/]*)\//.exec(p);
  return m ? m[1] : null;
}

/**
 * The generation to RECORD for a session nobody recorded one for — one `cc`
 * made, or one that was running when this daemon first learned generations.
 *
 * tmux's `#{session_created}` is the launch time: a session born since the
 * current generation began was launched under it. One born before was launched
 * under some earlier wiring, and nothing can say which, so it stays unrecorded
 * and reads `stale` once — the contract's "sessions launched before the daemon
 * recorded gens count as stale once".
 *
 * @param createdAt  epoch seconds (tmux)
 * @param current    `{gen, claude, since}` (since in epoch seconds), or null
 */
function assignGen({ createdAt, current }) {
  if (!current || !current.gen) return null;
  const born = Number(createdAt);
  if (!Number.isFinite(born) || born <= 0) return null;
  if (!Number.isFinite(Number(current.since)) || born < Number(current.since)) return null;
  return { gen: current.gen, claude: current.claude ?? null };
}

/**
 * The `runtime` field of a session row.
 *
 * `stale` is "the current generation is known and this session's is not it";
 * a session with no recorded generation is stale once a generation exists. With
 * no current generation yet (the daemon is still reading its inputs) nothing is
 * called stale: a false `stale` would start a cycle on no evidence.
 */
function runtimeFor({ recorded = null, current = null, restarting = false } = {}) {
  const gen = recorded && recorded.gen ? String(recorded.gen) : null;
  const cur = current && current.gen ? String(current.gen) : null;
  return {
    stale: !!cur && gen !== cur,
    gen,
    current: cur,
    claude: recorded && recorded.claude ? String(recorded.claude) : null,
    currentClaude: current && current.claude ? String(current.claude) : null,
    restarting: !!restarting,
  };
}

// ---------------------------------------------------------- the safe point

/** The words a restart can be held by, in the order of harm. */
const HOLD_WORDS = Object.freeze(['running', 'dialog', 'draft', 'background', 'attached', 'load']);

/**
 * What holds a restart right now, or null: the first true hold in the order of
 * harm. `running` is a turn in progress (the state file's word); `dialog` a
 * selector on screen or a question waiting; `draft` unsent text in the box;
 * `background` a shell or agent the session is still running; `attached` a
 * terminal on the session (skipped by `force`, and by nothing else); `load` the
 * host outside the lull the release cuts wait for (`lull === false`; null and
 * true are no hold).
 */
function safePointHold({
  state = null, dialog = false, draft = false, bg = false, attached = false, force = false, lull = true,
} = {}) {
  if (state === 'running') return 'running';
  if (dialog || state === 'attention') return 'dialog';
  if (draft) return 'draft';
  if (bg) return 'background';
  if (attached && !force) return 'attached';
  if (lull === false) return 'load';
  return null;
}

/**
 * The send queue's own hold word in the restart route's vocabulary, for the
 * `blockedBy` a 202 carries when the queue decided before the job looked.
 * `shell` (no Claude in the pane) has no word here: the job fails on it.
 */
function holdWord(queueWord) {
  switch (queueWord) {
    case 'turn': case 'starting': return 'running';
    case 'modal': case 'trust': case 'attention': return 'dialog';
    case 'draft': return 'draft';
    default: return HOLD_WORDS.includes(queueWord) ? queueWord : null;
  }
}

/**
 * `some avg10=4.56 avg60=11.59 …` from `cpu.pressure` → the avg10 figure, or
 * null when the text is not PSI.
 */
function pressureAvg10(text) {
  const m = /^some\s+avg10=([\d.]+)/m.exec(String(text || ''));
  return m ? Number(m[1]) : null;
}

/**
 * The lull the release cuts wait for before the appd suite: the container's own
 * `cpu.pressure` `some avg10` under 5 % (`/proc/loadavg` in an LXC is the
 * hypervisor's and never settles). A host with no PSI file is not gated.
 */
function pressureLull(text, { avg10Max = 5 } = {}) {
  const v = pressureAvg10(text);
  return v === null ? true : v < avg10Max;
}

// ------------------------------------------------------------- the process

const SHELLS = new Set(['sh', 'bash', 'zsh', 'dash', 'fish', 'ksh', 'ash', 'login']);
const CLAUDE_SCRIPT_RE = /(?:claude-code|\/claude)\/cli\.js$/;

/** Is this argv (and comm) Claude Code: the native binary, or node running its cli.js? */
function looksLikeClaude(argv, comm) {
  if (comm === 'claude') return true;
  if (!Array.isArray(argv) || !argv.length) return false;
  const b0 = path.basename(String(argv[0]));
  if (b0 === 'claude' || b0 === 'claude.exe') return true;
  return argv.some((a) => CLAUDE_SCRIPT_RE.test(String(a)));
}

function isShell(argv, comm) {
  const b0 = Array.isArray(argv) && argv.length ? path.basename(String(argv[0])).replace(/^-/, '') : '';
  return SHELLS.has(b0) || SHELLS.has(String(comm || ''));
}

/**
 * Which process under the pane is the session's Claude Code.
 *
 * `pids` are the pane process's descendants, nearest first. The first that is
 * Claude by name wins (looksLikeClaude); failing that, the first that is not a
 * shell — the pane runs `claude; exec "$SHELL" -l` through `sh -c`, so what the
 * shell is running is the program. Null when nothing but shells is there (Claude
 * has exited to the login shell).
 */
function pickClaudePid(pids, { argvOf, commOf }) {
  const seen = [];
  for (const pid of pids || []) {
    const argv = argvOf(pid);
    const comm = commOf(pid);
    if (!argv && !comm) continue;
    if (looksLikeClaude(argv, comm)) return pid;
    seen.push({ pid, argv, comm });
  }
  const other = seen.find((p) => !isShell(p.argv, p.comm));
  return other ? other.pid : null;
}

/**
 * The flags after the program: `node …/cli.js <flags>` is the flags after the
 * script, `claude <flags>` the flags after argv[0].
 */
function claudeArgs(argv) {
  if (!Array.isArray(argv) || !argv.length) return [];
  const b0 = path.basename(String(argv[0]));
  if (/^(node|nodejs|bun)(\d+)?$/.test(b0)) {
    const i = argv.findIndex((a, k) => k > 0 && /\.[cm]?js$/.test(String(a)));
    return i >= 0 ? argv.slice(i + 1) : argv.slice(1);
  }
  return argv.slice(1);
}

/**
 * The same flags with `--resume <id>` at the head, replacing whatever the
 * session was started with: `--resume [id]`, `--resume=id`, `-r [id]`,
 * `--continue`, `-c`. `--resume` takes its value optionally, so a value is
 * consumed only when the next word is not a flag.
 */
function replayArgs(args, resumeId) {
  const out = [];
  const list = Array.isArray(args) ? args.map(String) : [];
  for (let i = 0; i < list.length; i++) {
    const a = list[i];
    if (a === '--resume' || a === '-r') {
      if (i + 1 < list.length && !list[i + 1].startsWith('-')) i++;
      continue;
    }
    if (a.startsWith('--resume=')) continue;
    if (a === '--continue' || a === '-c') continue;
    out.push(a);
  }
  return resumeId ? ['--resume', String(resumeId), ...out] : out;
}

/** One word, safe for `sh -c`: bare when it needs nothing, else single-quoted. */
function shellQuote(s) {
  const t = String(s);
  if (t === '') return "''";
  return /^[A-Za-z0-9_@%+=:,./-]+$/.test(t) ? t : `'${t.replace(/'/g, "'\\''")}'`;
}

/**
 * The shell command a relaunched pane runs: `claude` from PATH (the binary a
 * `claude update` just put there, not the path the old process ran from), the
 * replayed flags, and the same tail every session has, so a Claude that exits
 * drops to a login shell as before.
 */
function relaunchCommand(args) {
  return `${['claude', ...(args || []).map(shellQuote)].join(' ')}; exec "$SHELL" -l`;
}

// ------------------------------------------------------------------ the sweep

/**
 * The session the automatic sweep cycles next, or null: stale, not already
 * restarting, idle (its job would otherwise sit behind a turn while the rest
 * wait), with a Claude session to resume, unattached (an attached session stays
 * `stale` for a manual restart), not in `skip` (given up on, or queued), oldest
 * first.
 */
function sweepPick(rows, { skip = new Set() } = {}) {
  const fit = (rows || []).filter((r) => r && r.runtime && r.runtime.stale && !r.runtime.restarting
    && r.addressable !== false && r.state === 'idle' && !!r.claudeSessionId
    && !(Number(r.attachedClients) > 0) && !skip.has(r.name));
  fit.sort((a, b) => (Number(a.createdAt) || 0) - (Number(b.createdAt) || 0));
  return fit[0] || null;
}

module.exports = {
  stableStringify, settingsDigest, mcpDigest, claudeFingerprint, genOf, claudeVersionFromPath,
  assignGen, runtimeFor,
  HOLD_WORDS, safePointHold, holdWord, pressureAvg10, pressureLull,
  looksLikeClaude, isShell, pickClaudePid, claudeArgs, replayArgs, shellQuote, relaunchCommand,
  sweepPick,
};
