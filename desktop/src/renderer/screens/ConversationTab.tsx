// The session's Conversation tab: the rendered Claude transcript, polled and
// tail-followed. The SessionView header already shows name/model/permission
// mode, so the meta line here carries only what the transcript adds (model as
// a person reads it, effort, branch).

import { useTranscript } from '../hooks/useTranscript'
import { TranscriptList } from '../components/transcript/TranscriptList'

export function ConversationTab({ name }: { name: string }): React.JSX.Element {
  const t = useTranscript('session', name)

  if (t.neverRan) {
    return (
      <div className="pane-placeholder">
        No conversation yet — this session has not prompted Claude.
      </div>
    )
  }
  if (t.page === null) {
    return (
      <div className="pane-placeholder">
        {t.error !== null ? `Transcript unavailable: ${t.error}` : 'Loading conversation…'}
      </div>
    )
  }

  const meta = [t.page.modelDisplay, t.page.effort, t.page.gitBranch].filter(
    (x): x is string => x !== null && x !== '',
  )
  return (
    <div className="conversation-tab">
      {meta.length > 0 ? <div className="transcript-meta">{meta.join(' · ')}</div> : null}
      {t.error !== null ? (
        <div className="banner banner-warn">transcript refresh failing: {t.error}</div>
      ) : null}
      <TranscriptList page={t.page} />
    </div>
  )
}
