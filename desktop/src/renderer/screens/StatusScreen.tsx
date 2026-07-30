// Host + plan + usage at a glance. The daemon caches the expensive parts;
// this screen just asks on entry and on a slow tick while visible.

import { useEffect, useState } from 'react'
import type { Plan, Status, Usage } from '../../shared/api/types'
import { call } from '../lib/ipc'

const fmtTokens = (n: number): string => {
  if (n >= 1_000_000_000) return `${(n / 1_000_000_000).toFixed(1)}B`
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}k`
  return String(n)
}

const sevClass = (severity: string): string =>
  severity === 'critical' ? 'bar-critical' : severity === 'warning' ? 'bar-warn' : 'bar-ok'

export function StatusScreen(): React.JSX.Element {
  const [status, setStatus] = useState<Status | null>(null)
  const [plan, setPlan] = useState<Plan | null>(null)
  const [usage, setUsage] = useState<Usage | null>(null)

  useEffect(() => {
    let alive = true
    const load = (): void => {
      void call('host.status').then((s) => alive && setStatus(s)).catch(() => {})
      void call('host.plan').then((p) => alive && setPlan(p)).catch(() => {})
      void call('host.usage').then((u) => alive && setUsage(u)).catch(() => {})
    }
    load()
    const t = setInterval(load, 30_000)
    return () => {
      alive = false
      clearInterval(t)
    }
  }, [])

  return (
    <div className="status">
      <h2>Host</h2>
      {status === null ? (
        <div className="pane-placeholder">Loading…</div>
      ) : (
        <div className="status-grid">
          <div>{status.host ?? '?'}</div>
          <div>up {Math.floor(status.uptimeSec / 3600)}h</div>
          <div>
            load {status.load.map((l) => l.toFixed(1)).join(' ')} / {status.cores} cores
          </div>
          <div>
            disk {status.disk?.used ?? '?'} / {status.disk?.size ?? '?'} ({status.disk?.usedPercent ?? '?'})
          </div>
          <div>{status.claude ?? ''}</div>
          <div>appd {status.appdVersion ?? '?'}</div>
          <div>mempalace {status.mempalace ?? '?'}</div>
          <div>
            {status.sessions} sessions · {status.chatsRunning} chats running
          </div>
        </div>
      )}

      <h2>Plan</h2>
      {plan === null ? (
        <div className="pane-placeholder">Loading…</div>
      ) : plan.error !== null ? (
        <div className="banner banner-warn">{plan.error}</div>
      ) : (
        plan.limits.map((l) => (
          <div key={l.label} className="plan-row">
            <span className="plan-label">
              {l.label}
              {l.isActive ? ' ·' : ''}
            </span>
            <div className="plan-bar">
              <div
                className={`plan-fill ${sevClass(l.severity)}`}
                style={{ width: `${Math.min(100, l.percent)}%` }}
              />
            </div>
            <span className="plan-pct">{Math.round(l.percent)}%</span>
          </div>
        ))
      )}

      <h2>Tokens</h2>
      {usage === null || usage.data === null ? (
        <div className="pane-placeholder">{usage?.error ?? 'Loading…'}</div>
      ) : (
        <div className="status-grid">
          <div>today {fmtTokens(usage.data.today?.totalTokens ?? 0)}</div>
          <div>7 days {fmtTokens(usage.data.week.totalTokens)}</div>
          <div>
            cache-read{' '}
            {usage.data.week.totalTokens > 0
              ? Math.round((usage.data.week.cacheReadTokens / usage.data.week.totalTokens) * 100)
              : 0}
            %
          </div>
          {usage.stale ? <div className="dim">stale{usage.refreshing ? ' · refreshing' : ''}</div> : null}
        </div>
      )}
    </div>
  )
}
