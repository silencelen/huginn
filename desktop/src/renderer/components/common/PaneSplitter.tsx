// The drag handle between the list pane and the detail pane.
//
// It is NOT a grid item. Adding a fourth child to `.shell` would shift the
// auto-placement the whole layout rests on (and `.detail-wide` spans `2 / -1`
// by hand), so the handle is absolutely positioned over the seam and the grid
// stays exactly as declarative as it was — one custom property, `--list-w`, is
// the only thing that changes.
//
// The drag is measured as a delta from where it started rather than from the
// rail's width, so nothing here has to know how wide the rail is.

import { useEffect, useRef, useState } from 'react'
import { clampListWidth, LIST_W_DEFAULT, LIST_W_MAX, LIST_W_MIN } from './paneSplit'

/** Arrow-key step, and the coarse step with Shift held. */
const STEP = 16
const BIG_STEP = 48

export function PaneSplitter(props: {
  /** Current width in px — the same value the shell puts in `--list-w`. */
  width: number
  /** Live during a drag: cheap, so the pane tracks the pointer. */
  onChange: (px: number) => void
  /** Once, when the gesture ends: this is the one that gets persisted. */
  onSettle: (px: number) => void
}): React.JSX.Element {
  const [dragging, setDragging] = useState(false)
  const latest = useRef(props.width)
  latest.current = props.width

  // While dragging, the cursor must not flicker back to `text` whenever the
  // pointer crosses a pane, and a drag over a list must not select its rows.
  useEffect(() => {
    if (!dragging) return
    const body = document.body
    const cursor = body.style.cursor
    const select = body.style.userSelect
    body.style.cursor = 'col-resize'
    body.style.userSelect = 'none'
    return () => {
      body.style.cursor = cursor
      body.style.userSelect = select
    }
  }, [dragging])

  const onMouseDown = (e: React.MouseEvent): void => {
    if (e.button !== 0) return
    e.preventDefault()
    const startX = e.clientX
    const startW = props.width
    setDragging(true)
    const move = (ev: MouseEvent): void => {
      const next = clampListWidth(startW + (ev.clientX - startX))
      latest.current = next
      props.onChange(next)
    }
    const up = (): void => {
      window.removeEventListener('mousemove', move)
      window.removeEventListener('mouseup', up)
      setDragging(false)
      props.onSettle(latest.current)
    }
    window.addEventListener('mousemove', move)
    window.addEventListener('mouseup', up)
  }

  const set = (px: number): void => {
    const next = clampListWidth(px)
    latest.current = next
    props.onChange(next)
    props.onSettle(next)
  }

  const onKeyDown = (e: React.KeyboardEvent): void => {
    const step = e.shiftKey ? BIG_STEP : STEP
    if (e.key === 'ArrowLeft') set(props.width - step)
    else if (e.key === 'ArrowRight') set(props.width + step)
    else if (e.key === 'Home' || e.key === 'Enter') set(LIST_W_DEFAULT)
    else return
    e.preventDefault()
  }

  return (
    <div
      className={`pane-splitter ${dragging ? 'dragging' : ''}`}
      style={{ left: `calc(var(--rail-w, 76px) + ${props.width}px)` }}
      role="separator"
      aria-orientation="vertical"
      aria-label="Resize the list pane"
      aria-valuenow={props.width}
      aria-valuemin={LIST_W_MIN}
      aria-valuemax={LIST_W_MAX}
      tabIndex={0}
      onMouseDown={onMouseDown}
      onDoubleClick={() => set(LIST_W_DEFAULT)}
      onKeyDown={onKeyDown}
    />
  )
}
