// One open session: a calm header (Claude's own title, one meta line, the tab
// switch), the Conversation | Screen tabs, the work strip, and the shared
// composer. Destructive verbs live in the overflow menu rather than as loud
// buttons in the title row, and Interrupt is NOT duplicated here — the
// composer's Stop already sends Escape (same verb, one control).

import { useEffect, useRef, useState } from 'react'
import { useApp } from '../stores/app'
import { useWindowVisible } from '../hooks/useVisible'
import { call } from '../lib/ipc'
import { Composer } from '../components/composer/Composer'
import { WorkStrip } from '../components/workstrip/WorkStrip'
import { ConfirmDialog, InputDialog } from '../components/common/Dialog'
import { ConversationTab } from './ConversationTab'
import { ScreenTab } from './ScreenTab'

type Tab = 'conversation' | 'screen'

export function SessionView({ name }: { name: string }): React.JSX.Element {
  const [tab, setTab] = useState<Tab>('conversation')
  const [menuOpen, setMenuOpen] = useState(false)
  const [renaming, setRenaming] = useState(false)
  const [killing, setKilling] = useState(false)
  const [gone, setGone] = useState(false)
  const sessions = useApp((s) => s.sessions)
  const navigate = useApp((s) => s.navigate)
  const refreshSessions = useApp((s) => s.refreshSessions)
  const row = sessions?.find((s) => s.name === name) ?? null
  const visible = useWindowVisible()

  // A rename navigates to the new name before the list has refreshed, so the
  // "session vanished" check must not fire on that one stale observation —
  // it needs to be missing twice running.
  const missesRef = useRef(0)
  useEffect(() => {
    if (sessions === null) return
    if (row !== null) {
      missesRef.current = 0
      return
    }
    missesRef.current += 1
    if (missesRef.current >= 2) {
      setGone(true)
      navigate({ view: 'sessions', sessionName: null })
    }
  }, [sessions, row, navigate])

  useEffect(() => {
    missesRef.current = 0
    setGone(false)
    setMenuOpen(false)
  }, [name])

  const act = (fn: () => Promise<unknown>): void => {
    void fn()
      .then(() => refreshSessions())
      .catch((e: unknown) => window.alert(e instanceof Error ? e.message : String(e)))
  }

  return (
    <div className="session-view">
      {renaming ? (
        <InputDialog
          title="Rename session"
          label="Lowercase letters, digits, underscore, dot or dash."
          initial={name}
          onCancel={() => setRenaming(false)}
          onSubmit={(to) => {
            setRenaming(false)
            const canon = to.toLowerCase()
            if (canon === name) return
            act(async () => {
              await call('sessions.rename', name, canon)
              navigate({ view: 'sessions', sessionName: canon })
            })
          }}
        />
      ) : null}
      {killing ? (
        <ConfirmDialog
          title="Kill session"
          body={`Kill ${name}? Claude Code and anything it is running in that pane stop immediately.`}
          confirmLabel="Kill"
          danger
          onCancel={() => setKilling(false)}
          onConfirm={() => {
            setKilling(false)
            act(async () => {
              await call('sessions.kill', name)
              navigate({ view: 'sessions', sessionName: null })
            })
          }}
        />
      ) : null}

      <header className="view-header">
        <div className="view-title-row">
          <div className="view-title" title={row?.title ?? name}>
            {row?.title ?? name}
          </div>
          <div className="view-actions">
            <div className="menu-wrap">
              <button
                type="button"
                className="overflow-btn"
                title="Session actions"
                onClick={() => setMenuOpen((o) => !o)}
              >
                ⋯
              </button>
              {menuOpen ? (
                <>
                  <div className="menu-scrim" onClick={() => setMenuOpen(false)} />
                  <div className="menu">
                    <button
                      type="button"
                      onClick={() => {
                        setMenuOpen(false)
                        setRenaming(true)
                      }}
                    >
                      Rename session
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setMenuOpen(false)
                        act(() => call('sessions.keys', name, { keys: ['Escape'] }))
                      }}
                    >
                      Interrupt (Esc)
                    </button>
                    <button
                      type="button"
                      className="menu-danger"
                      onClick={() => {
                        setMenuOpen(false)
                        setKilling(true)
                      }}
                    >
                      Kill session
                    </button>
                  </div>
                </>
              ) : null}
            </div>
          </div>
        </div>
        <div className="view-sub">
          <span className="view-meta">{name}</span>
          {row?.liveModel != null ? <span className="view-meta">{row.liveModel}</span> : null}
          {row?.permissionMode != null ? (
            <span className="view-meta">{row.permissionMode}</span>
          ) : null}
          {/* Geometry only matters while WE are holding the window to our shape. */}
          {row?.sizeLeased === true ? (
            <span className="view-meta">
              fitted {row.cols}×{row.rows}
            </span>
          ) : null}
          <div className="seg tab-seg" role="tablist">
            <button
              type="button"
              role="tab"
              aria-selected={tab === 'conversation'}
              className={`seg-btn ${tab === 'conversation' ? 'seg-on' : ''}`}
              onClick={() => setTab('conversation')}
            >
              Conversation
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={tab === 'screen'}
              className={`seg-btn ${tab === 'screen' ? 'seg-on' : ''}`}
              onClick={() => setTab('screen')}
            >
              Screen
            </button>
          </div>
        </div>
      </header>

      {gone ? (
        <div className="banner banner-warn">This session ended.</div>
      ) : (
        <>
          {/* Both tabs stay mounted: flipping used to refetch the transcript
              cold and drop/reacquire the pane lease every time. */}
          <div className={`tab-host ${tab === 'conversation' ? '' : 'tab-hidden'}`}>
            <ConversationTab name={name} active={visible && tab === 'conversation'} />
          </div>
          <div className={`tab-host ${tab === 'screen' ? '' : 'tab-hidden'}`}>
            <ScreenTab name={name} active={visible && tab === 'screen'} />
          </div>
        </>
      )}

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
