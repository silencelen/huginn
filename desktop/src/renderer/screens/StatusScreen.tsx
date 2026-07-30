// Host + plan + usage at a glance. The daemon caches the expensive parts;
// this screen just asks on entry and on a slow tick while visible. Values
// persist between refreshes — a failed refresh shows a stale note, not a
// "Loading…" flash.

import { useEffect, useState } from 'react'
import type { Plan, Status, Usage } from '../../shared/api/types'
import { call } from '../lib/ipc'

const fmtTokens = (n: number): string => {
  if (n >= 1_000_000_000) return `${(n / 1_000_000_000).toFixed(1)}B`
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`
  return String(n)
}

const fmtUptime = (sec: number): string => {
  const d = Math.floor(sec / 86_400)
  const h = Math.floor((sec % 86_400) / 3600)
  const m = Math.floor((sec % 3600) / 60)
  if (d > 0) return `${d}d ${h}h`
  if (h > 0) return `${h}h ${m}m`
  return `${m}m`
}

/** "resets in 3h 12m" from the ISO resetsAt — the number that stops work. */
const fmtReset = (iso: string | null): string | null => {
  if (iso === null) return null
  const ms = Date.parse(iso) - Date.now()
  if (Number.isNaN(ms)) return null
  if (ms <= 0) return 'resets soon'
  const min = Math.floor(ms / 60_000)
  const d = Math.floor(min / 1440)
  const h = Math.floor((min % 1440) / 60)
  const m = min % 60
  if (d > 0) return `resets in ${d}d ${h}h`
  if (h > 0) return `resets in ${h}h ${m}m`
  return `resets in ${m}m`
}

const sevClass = (severity: string): string =>
  severity === 'critical' ? 'bar-critical' : severity === 'warning' ? 'bar-warn' : 'bar-ok'

/** "43%" → 43; the daemon reports disk percent as df's string. */
const pctOf = (s: string | null): number | null => {
  const m = /(\d+(?:\.\d+)?)/.exec(s ?? '')
  return m === null ? null : Number(m[1])
}

const diskClass = (pct: number | null): string =>
  pct === null ? 'bar-ok' : pct >= 90 ? 'bar-critical' : pct >= 70 ? 'bar-warn' : 'bar-ok'

const fmtCost = (usd: number): string => (usd >= 100 ? `$${Math.round(usd)}` : `$${usd.toFixed(2)}`)

export function StatusScreen(): React.JSX.Element {
  const [status, setStatus] = useState<Status | null>(null)
  const [plan, setPlan] = useState<Plan | null>(null)
  const [usage, setUsage] = useState<Usage | null>(null)
  const [appVersion, setAppVersion] = useState<string | null>(null)
  const [stale, setStale] = useState(false)

  useEffect(() => {
    let alive = true
    void call('app.version').then((v) => alive && setAppVersion(v)).catch(() => {})
    const load = (): void => {
      void Promise.allSettled([
        call('host.status').then((s) => alive && setStatus(s)),
        call('host.plan').then((p) => alive && setPlan(p)),
        call('host.usage').then((u) => alive && setUsage(u)),
      ]).then((results) => {
        if (alive) setStale(results.some((r) => r.status === 'rejected'))
      })
    }
    load()
    const t = setInterval(load, 30_000)
    return () => {
      alive = false
      clearInterval(t)
    }
  }, [])

  const loadHigh = status !== null && status.cores > 0 && status.load[0] !== undefined
    ? status.load[0] / status.cores
    : 0
  const diskPct = pctOf(status?.disk?.usedPercent ?? null)
  const week = usage?.data?.week ?? null
  const cachePct =
    week !== null && week.totalTokens > 0
      ? Math.round((week.cacheReadTokens / week.totalTokens) * 100)
      : null

  return (
    <div className="status">
      {stale ? (
        <div className="stale-mark">Host unreachable — showing the last known values.</div>
      ) : null}

      <h2>Host</h2>
      {status === null ? (
        <div className="dim">Loading…</div>
      ) : (
        <div className="kv-grid">
          <span className="kv-label">Machine</span>
          <span>{status.host ?? 'unknown'}</span>

          <span className="kv-label">Uptime</span>
          <span>{fmtUptime(status.uptimeSec)}</span>

          <span className="kv-label">Load</span>
          <span>
            <span className={loadHigh >= 1 ? 'bad' : loadHigh >= 0.7 ? 'load-warn' : ''}>
              {status.load.map((l) => l.toFixed(2)).join('  ')}
            </span>
            <span className="kv-note"> / {status.cores} cores</span>
          </span>

          <span className="kv-label">Disk</span>
          <span>
            <span className="kv-meter">
              <span
                className={`kv-meter-fill ${diskClass(diskPct)}`}
                style={{ width: `${Math.min(100, diskPct ?? 0)}%` }}
              />
            </span>
            {status.disk?.used ?? '?'} of {status.disk?.size ?? '?'}
            <span className="kv-note"> ({status.disk?.usedPercent ?? '?'} used)</span>
          </span>
        </div>
      )}

      <h2>Agent</h2>
      {status === null ? (
        <div className="dim">Loading…</div>
      ) : (
        <div className="kv-grid">
          <span className="kv-label">Claude Code</span>
          <span>{status.claude ?? 'unknown'}</span>

          <span className="kv-label">appd</span>
          <span>{status.appdVersion ?? 'unknown'}</span>

          <span className="kv-label">This app</span>
          <span>{appVersion !== null ? `v${appVersion}` : '…'}</span>

          <span className="kv-label">MemPalace</span>
          <span>{status.mempalace ?? 'unknown'}</span>
        </div>
      )}

      <h2>Activity</h2>
      {status === null ? (
        <div className="dim">Loading…</div>
      ) : (
        <div className="kv-grid">
          <span className="kv-label">Sessions</span>
          <span>{status.sessions}</span>

          <span className="kv-label">Chats running</span>
          <span>{status.chatsRunning}</span>
        </div>
      )}

      <h2>Plan</h2>
      {plan === null ? (
        <div className="dim">Loading…</div>
      ) : plan.error !== null ? (
        <div className="banner banner-warn">{plan.error}</div>
      ) : (
        plan.limits.map((l) => (
          <div key={l.label} className="plan-row">
            <span className={`plan-label ${l.isActive ? 'plan-active' : ''}`}>{l.label}</span>
            <div className="plan-bar">
              <div
                className={`plan-fill ${sevClass(l.severity)}`}
                style={{ width: `${Math.min(100, l.percent)}%` }}
              />
            </div>
            <span className="plan-pct">{Math.round(l.percent)}%</span>
            <span className="plan-reset">{fmtReset(l.resetsAt) ?? ''}</span>
          </div>
        ))
      )}

      <h2>Tokens</h2>
      {usage === null ? (
        <div className="dim">Loading…</div>
      ) : usage.data === null ? (
        <div className="dim">{usage.error ?? 'No usage data yet.'}</div>
      ) : (
        <>
          <div className="kv-grid">
            <span className="kv-label">Today</span>
            <span>{fmtTokens(usage.data.today?.totalTokens ?? 0)}</span>

            <span className="kv-label">Last 7 days</span>
            <span>{fmtTokens(usage.data.week.totalTokens)}</span>

            <span className="kv-label">Cache reads</span>
            <span>
              {cachePct !== null ? `${cachePct}%` : '—'}
              <span className="kv-note"> of 7-day tokens</span>
            </span>

            {week !== null && week.costUsd !== null ? (
              <>
                <span className="kv-label">7-day cost</span>
                <span>
                  {fmtCost(week.costUsd)}
                  <span className="kv-note">
                    {usage.costIsEstimate
                      ? ' list-price estimate, not billed spend'
                      : ' at list prices'}
                  </span>
                </span>
              </>
            ) : null}
          </div>
          {usage.stale ? (
            <div className="stale-mark">
              Usage cached{usage.refreshing ? ' · refreshing' : ''}
            </div>
          ) : null}
        </>
      )}
    </div>
  )
}
