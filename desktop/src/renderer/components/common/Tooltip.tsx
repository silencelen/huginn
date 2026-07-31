// One tooltip for the whole shell, by delegation.
//
// Not a wrapper component: a `<Tooltip>` around every dot would add a span to
// every row's flex line, and the things most worth explaining here — an 8px
// state dot, a two-word badge — are exactly the things you cannot afford to
// wrap. So a single layer mounted once in App listens for hovers and asks the
// hovered element what it has to say. Two ways to answer:
//
//   1. `data-tip="…"` on any element we render. Zero layout cost, and the copy
//      sits next to the thing it describes.
//   2. The list rows, whose markup belongs to another module. Those are matched
//      by class inside `.list-pane .row`, resolved back to the store row by
//      position, and VERIFIED against the text that row is displaying before we
//      say a word — a tooltip describing the wrong row is worse than none, so a
//      mismatch stays silent. (When the row components can carry `data-tip`
//      themselves, delete `rowTip` and hand them the string; this indirection is
//      only here because they are not ours to edit.)
//
// Native `title` is not an option: a second of delay, an OS-styled box that
// ignores the app's palette, and no way to say more than a fragment.

import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useApp } from '../../stores/app'
import { chatDotTip, queuedTip, sessionDotTip } from './tips'

/** Long enough that skimming a list never flickers, short enough to feel free. */
const SHOW_DELAY_MS = 400

/** Room needed below the host before the tip prefers to sit under it. */
const BELOW_CLEARANCE = 56

/**
 * Hosts narrower than this are anchored by their left edge instead of centred.
 * An 8px dot centred under a 260px tip hangs 130px out to either side, which
 * over a rail or a window edge reads as a tip belonging to something else.
 */
const NARROW_HOST = 24

interface Tip {
  text: string
  x: number
  y: number
  below: boolean
  centred: boolean
}

const text = (root: Element, sel: string): string | null =>
  root.querySelector(sel)?.textContent ?? null

/**
 * A state dot or queued badge in one of the list panes, resolved against the
 * store. Returns null unless the row's own rendered text confirms the match.
 */
function rowTip(el: Element): string | null {
  const row = el.closest('.row')
  const pane = row === null ? null : row.closest('.list-pane')
  if (row === null || pane === null) return null
  const index = Array.from(pane.querySelectorAll('.row')).indexOf(row)
  if (index < 0) return null

  const { dest, chats, sessions } = useApp.getState()
  const now = Math.floor(Date.now() / 1000)

  if (dest.view === 'chats') {
    const chat = chats[index]
    if (chat === undefined) return null
    if (text(row, '.row-title') !== (chat.title ?? 'Untitled')) return null
    if (el.classList.contains('queued-badge')) return queuedTip(chat.pending)
    return chatDotTip(chat, now)
  }
  if (dest.view === 'sessions') {
    const session = sessions?.[index]
    if (session === undefined) return null
    if (text(row, '.row-name') !== session.name) return null
    if (el.classList.contains('state-dot')) return sessionDotTip(session, now)
    return null
  }
  return null
}

/** The nearest ancestor-or-self that has something to say, if any. */
function tipHost(el: Element): Element | null {
  const declared = el.closest('[data-tip]')
  if (declared !== null) return declared
  const marker = el.closest('.state-dot, .queued-badge')
  if (marker !== null && marker.closest('.list-pane .row') !== null) return marker
  return null
}

function tipText(host: Element): string | null {
  const declared = host.getAttribute('data-tip')
  if (declared !== null) return declared === '' ? null : declared
  return rowTip(host)
}

export function TooltipLayer(): React.JSX.Element | null {
  const [tip, setTip] = useState<Tip | null>(null)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const host = useRef<Element | null>(null)
  const box = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    const hide = (): void => {
      if (timer.current !== null) {
        clearTimeout(timer.current)
        timer.current = null
      }
      host.current = null
      setTip((cur) => (cur === null ? cur : null))
    }

    // mouseover alone is enough: it fires for every element entered, so moving
    // within one host resolves to the same host (no-op) and moving off it
    // resolves to null (hide). No mouseout bookkeeping, no leak on unmount of
    // the hovered row.
    const onOver = (e: MouseEvent): void => {
      const target = e.target
      if (!(target instanceof Element)) return
      const next = tipHost(target)
      if (next === null) {
        if (host.current !== null) hide()
        return
      }
      if (next === host.current) return
      hide()
      host.current = next
      timer.current = setTimeout(() => {
        timer.current = null
        // The row may have been re-rendered away during the delay.
        if (host.current !== next || !next.isConnected) return
        const content = tipText(next)
        if (content === null || content === '') return
        const r = next.getBoundingClientRect()
        const below = r.bottom + BELOW_CLEARANCE < window.innerHeight
        const centred = r.width >= NARROW_HOST
        setTip({
          text: content,
          x: centred ? r.left + r.width / 2 : r.left,
          y: below ? r.bottom + 8 : r.top - 8,
          below,
          centred,
        })
      }, SHOW_DELAY_MS)
    }

    document.addEventListener('mouseover', onOver, true)
    // Anything that would make the position a lie, or that means the user is
    // busy doing something else.
    document.addEventListener('mouseleave', hide)
    window.addEventListener('mousedown', hide, true)
    window.addEventListener('keydown', hide, true)
    window.addEventListener('wheel', hide, true)
    window.addEventListener('scroll', hide, true)
    window.addEventListener('resize', hide)
    window.addEventListener('blur', hide)
    return () => {
      if (timer.current !== null) clearTimeout(timer.current)
      document.removeEventListener('mouseover', onOver, true)
      document.removeEventListener('mouseleave', hide)
      window.removeEventListener('mousedown', hide, true)
      window.removeEventListener('keydown', hide, true)
      window.removeEventListener('wheel', hide, true)
      window.removeEventListener('scroll', hide, true)
      window.removeEventListener('resize', hide)
      window.removeEventListener('blur', hide)
    }
  }, [])

  // Nudge back on screen after measuring, rather than guessing a width.
  useLayoutEffect(() => {
    const el = box.current
    if (el === null) return
    el.style.marginLeft = ''
    const r = el.getBoundingClientRect()
    const margin = 8
    const dx =
      r.left < margin
        ? margin - r.left
        : r.right > window.innerWidth - margin
          ? window.innerWidth - margin - r.right
          : 0
    if (dx !== 0) el.style.marginLeft = `${dx}px`
  }, [tip])

  if (tip === null) return null
  return createPortal(
    <div
      ref={box}
      className="tip"
      role="tooltip"
      style={{
        left: tip.x,
        top: tip.y,
        transform: `translate(${tip.centred ? '-50%' : '0'}, ${tip.below ? '0' : '-100%'})`,
      }}
    >
      {tip.text}
    </div>,
    document.body,
  )
}
