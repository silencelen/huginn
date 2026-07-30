// The keys a composer cannot say: Esc, Tab/BTab, arrows, control chords,
// paging — one horizontally scrollable row of chips, mirroring the Android
// KeyRow. The Live chip leads because it changes what the whole keyboard
// means (every keystroke goes straight to the pane).

const KEYS: ReadonlyArray<readonly [label: string, key: string]> = [
  ['Esc', 'Escape'],
  ['⇧Tab', 'BTab'],
  ['Tab', 'Tab'],
  ['↑', 'Up'],
  ['↓', 'Down'],
  ['←', 'Left'],
  ['→', 'Right'],
  ['^C', 'C-c'],
  ['^D', 'C-d'],
  ['^L', 'C-l'],
  ['^R', 'C-r'],
  ['PgUp', 'PPage'],
  ['PgDn', 'NPage'],
]

export function KeyRow({
  onKey,
  live,
  onToggleLive,
}: {
  onKey: (key: string) => void
  live: boolean
  onToggleLive: () => void
}): React.JSX.Element {
  return (
    <div className="term-keyrow">
      <button
        type="button"
        className={`term-chip${live ? ' term-chip-on' : ''}`}
        title="Type straight into the pane, key by key"
        onClick={onToggleLive}
      >
        ⌨ Live
      </button>
      {KEYS.map(([label, key]) => (
        <button key={key} type="button" className="term-chip" title={key} onClick={() => onKey(key)}>
          {label}
        </button>
      ))}
    </div>
  )
}
