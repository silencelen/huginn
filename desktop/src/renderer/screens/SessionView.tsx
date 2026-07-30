// One open session: header (Claude's own title + live meta), the
// Conversation | Screen tabs, the work strip, session actions, and the shared
// composer (text lands on the pane as keys + Enter).

import { useEffect, useState } from 'react'
import { useApp } from '../stores/app'
import { call } from '../lib/ipc'
import { Composer } from '../components/composer/Composer'
import { WorkStrip } from '../components/workstrip/WorkStrip'
import { ConversationTab } from './ConversationTab'
import { ScreenTab } from './ScreenTab'

type Tab = 'conversation' | 'screen'

export function SessionView({ name }: { name: string }): React.JSX.Element {
  const [tab, setTab] = useState<Tab>('conversation')
  const sessions = useApp((s) => s.sessions)
  const navigate = useApp((s) => s.navigate)
  const refreshSessions = useApp((s) => s.refreshSessions)
  const row = sessions?.find((s) => s.name === name) ?? null

  // Session killed under the viewer -> back to the list.
  useEffect(() => {
    if (sessions !== null && row === null) navigate({ view: 'sessions', sessionName: null })
  }, [sessions, row, navigate])

  const act = (fn: () => Promise<unknown>): void => {
    void fn()
      .then(() => refreshSessions())
      .catch((e: unknown) => window.alert(e instanceof Error ? e.message : String(e)))
  }

  return (
    <div className="session-view">
      <header className="view-header">
        <div className="view-title-row">
          <div className="view-title">{row?.title ?? name}</div>
          <div className="view-actions">
            <button
              type="button"
              title="Interrupt (Esc)"
              onClick={() => act(() => call('sessions.keys', name, { keys: ['Escape'] }))}
            >
              Esc
            </button>
            <button
              type="button"
              title="Rename session"
              onClick={() => {
                const to = window.prompt('Rename session to:', name)
                if (to !== null && to !== '' && to !== name)
                  act(async () => {
                    await call('sessions.rename', name, to)
                    navigate({ view: 'sessions', sessionName: to.toLowerCase() })
                  })
              }}
            >
              Rename
            </button>
            <button
              type="button"
              className="danger"
              title="Kill session"
              onClick={() => {
                if (window.confirm(`Kill session ${name}?`))
                  act(async () => {
                    await call('sessions.kill', name)
                    navigate({ view: 'sessions', sessionName: null })
                  })
              }}
            >
              Kill
            </button>
          </div>
        </div>
        <div className="view-sub">
          <span className="view-meta">{name}</span>
          {row?.liveModel !== null && row?.liveModel !== undefined ? (
            <span className="view-meta">{row.liveModel}</span>
          ) : null}
          {row?.permissionMode !== null && row?.permissionMode !== undefined ? (
            <span className="view-meta">{row.permissionMode}</span>
          ) : null}
          {row !== null ? (
            <span className="view-meta">
              {row.cols}x{row.rows}
              {row.sizeLeased ? ' · fitted' : ''}
            </span>
          ) : null}
          <span className="tab-switch">
            <button
              type="button"
              className={tab === 'conversation' ? 'tab-active' : ''}
              onClick={() => setTab('conversation')}
            >
              Conversation
            </button>
            <button
              type="button"
              className={tab === 'screen' ? 'tab-active' : ''}
              onClick={() => setTab('screen')}
            >
              Screen
            </button>
          </span>
        </div>
      </header>

      {tab === 'conversation' ? <ConversationTab name={name} /> : <ScreenTab name={name} />}

      <WorkStrip name={name} />

      <Composer
        draftKey={`sess:${name}`}
        running={row?.state === 'running'}
        seedText={null}
        onSeedConsumed={() => {}}
        onSend={(text) => act(() => call('sessions.keys', name, { text, keys: ['Enter'] }))}
        onStop={() => act(() => call('sessions.keys', name, { keys: ['Escape'] }))}
      />
    </div>
  )
}
