'use strict';
// Model ids, and turning them into something a person reads.
//
// "Opus" is no longer a sufficient label: Claude Code can run Opus 5 or Opus 4.8
// (and 4.7, 4.6…), so a control that says only "Opus" does not tell you what you
// are talking to. Everything here exists to keep the VERSION visible.
//
// The available list is discovered from the installed CLI rather than hardcoded,
// so it follows a `claude update` instead of quietly going stale. Discovery is
// best effort: if it finds nothing, callers fall back to the family aliases,
// which always work.

const fs = require('node:fs');
const { execFile } = require('node:child_process');

const FAMILIES = ['fable', 'opus', 'sonnet', 'haiku'];
const FAMILY_LABEL = { fable: 'Fable', opus: 'Opus', sonnet: 'Sonnet', haiku: 'Haiku' };

/**
 * Splits an id into family and version. Returns null for anything that is not a
 * recognisable model id, including a display name.
 *
 *   claude-opus-4-8            -> {family:'opus',   version:[4,8]}
 *   claude-haiku-4-5-20251001  -> {family:'haiku',  version:[4,5]}   (date dropped)
 */
function parseModelId(id) {
  if (typeof id !== 'string') return null;
  const bare = id.trim().toLowerCase().replace(/^claude-/, '');
  const m = /^([a-z]+)((?:-\d+)*)((?:-[a-z0-9]+)*)$/.exec(bare);
  if (!m) return null;
  const family = m[1];
  if (!FAMILIES.includes(family)) return null;
  let parts = m[2] ? m[2].slice(1).split('-').map(Number) : [];
  // A trailing 8-digit group is a release date, not a version component.
  if (parts.length && String(parts[parts.length - 1]).length === 8) parts = parts.slice(0, -1);
  // Anything alphabetic after the version names a VARIANT, not a version:
  // `claude-fable-5-mythos-5` is a different model that would otherwise offer a
  // second, indistinguishable "Fable 5" in the menu.
  return { family, version: parts, suffix: m[3] ? m[3].slice(1) : '' };
}

/** `claude-opus-4-8` -> `Opus 4.8`. A display name is passed through unchanged. */
function formatModel(model) {
  if (typeof model !== 'string' || !model.trim()) return null;
  const raw = model.trim();
  // Already a display name ("Opus 5", "Fable 5"): keep it, the pane wrote it.
  if (/\s/.test(raw) || /^[A-Z]/.test(raw)) return raw;
  const p = parseModelId(raw);
  if (!p) return raw;
  const label = FAMILY_LABEL[p.family] ?? p.family;
  return p.version.length ? `${label} ${p.version.join('.')}` : label;
}

/** Newest first, by version components. */
function compareVersions(a, b) {
  const n = Math.max(a.length, b.length);
  for (let i = 0; i < n; i++) {
    const d = (b[i] ?? -1) - (a[i] ?? -1);
    if (d) return d;
  }
  return 0;
}

/**
 * Picks the models worth offering from a pile of discovered ids: the newest few
 * of each family, skipping the variants that are not a plain choice (dated
 * snapshots, `-v1`, `-fast`) so the menu stays a menu.
 */
function selectModels(ids, perFamily = 2) {
  const byFamily = new Map();
  for (const id of new Set(ids)) {
    const bare = id.replace(/^claude-/, '');
    if (/-v\d+$/.test(bare) || /-fast$/.test(bare) || /-\d{8}(-|$)/.test(bare)) continue;
    const p = parseModelId(id);
    if (!p || !p.version.length || p.suffix) continue;
    const list = byFamily.get(p.family) ?? [];
    list.push({ id: `claude-${bare}`, family: p.family, version: p.version, display: formatModel(bare) });
    byFamily.set(p.family, list);
  }
  const out = [];
  for (const family of FAMILIES) {
    const list = (byFamily.get(family) ?? []).sort((a, b) => compareVersions(a.version, b.version));
    // Two ids that read the same would be an unpickable menu; keep the first.
    const seen = new Set();
    const unique = list.filter((m) => !seen.has(m.display) && seen.add(m.display));
    out.push(...unique.slice(0, perFamily).map(({ id, display, family }) => ({ id, display, family })));
  }
  return out;
}

/** The always-valid fallback: the family aliases the CLI documents. */
function aliasModels() {
  return FAMILIES.map((f) => ({ id: f, display: FAMILY_LABEL[f], family: f }));
}

// Discovery is a scan of the CLI executable, cached against its identity so it
// runs once per installed version rather than once per request.
let cache = { key: null, models: null };

function binaryKey(binPath) {
  try {
    const st = fs.statSync(binPath);
    return `${binPath}:${st.size}:${Math.floor(st.mtimeMs)}`;
  } catch { return null; }
}

function discoverModels(binPath, timeoutMs = 60_000) {
  const key = binaryKey(binPath);
  if (!key) return Promise.resolve(aliasModels());
  if (cache.key === key && cache.models) return Promise.resolve(cache.models);
  return new Promise((resolve) => {
    execFile('sh', ['-c',
      `strings -n 6 ${JSON.stringify(binPath)} | grep -oE 'claude-(fable|opus|sonnet|haiku)-[0-9][0-9a-z-]*' | sort -u`],
    { timeout: timeoutMs, maxBuffer: 8 * 1024 * 1024 }, (err, stdout) => {
      const ids = err ? [] : stdout.split('\n').map((l) => l.trim()).filter(Boolean);
      const picked = selectModels(ids);
      const models = picked.length ? picked : aliasModels();
      cache = { key, models };
      resolve(models);
    });
  });
}

module.exports = { parseModelId, formatModel, selectModels, aliasModels, discoverModels, compareVersions };
