// Right-click menus. One primitive, used by both list panes.
//
// It renders through a portal at fixed coordinates: a menu that lives inside
// `.list-pane` would scroll with the list it is describing, and the panes are
// scroll containers. It flips at the viewport edges rather than being clipped,
// and it closes on anything that would make its position a lie — a scroll, a
// resize, a click elsewhere, Escape.

import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'

export interface MenuItem {
  label: string
  danger?: boolean
  disabled?: boolean
  onClick: () => void
}

export function ContextMenu(props: {
  x: number
  y: number
  items: MenuItem[]
  onClose: () => void
}): React.JSX.Element {
  const ref = useRef<HTMLDivElement | null>(null)
  const [pos, setPos] = useState({ x: props.x, y: props.y })

  // Measure once mounted: the item labels decide the width, so the flip cannot
  // be computed before the browser has laid it out.
  useLayoutEffect(() => {
    const el = ref.current
    if (el === null) return
    const { width, height } = el.getBoundingClientRect()
    const margin = 8
    const x =
      props.x + width + margin > window.innerWidth
        ? Math.max(margin, props.x - width)
        : props.x
    const y =
      props.y + height + margin > window.innerHeight
        ? Math.max(margin, props.y - height)
        : props.y
    setPos({ x, y })
    el.querySelector<HTMLButtonElement>('button:not(:disabled)')?.focus()
  }, [props.x, props.y])

  // Deps are the callback alone: the row above re-renders on every list poll,
  // and re-subscribing five window listeners each time is pure churn.
  const onClose = props.onClose
  useEffect(() => {
    const onDown = (e: MouseEvent): void => {
      if (ref.current !== null && e.target instanceof Node && ref.current.contains(e.target)) return
      onClose()
    }
    const onKey = (e: KeyboardEvent): void => {
      if (e.key !== 'Escape') return
      e.preventDefault()
      e.stopPropagation()
      onClose()
    }
    const onScroll = (): void => onClose()

    window.addEventListener('mousedown', onDown, true)
    window.addEventListener('keydown', onKey, true)
    window.addEventListener('scroll', onScroll, true)
    window.addEventListener('resize', onScroll)
    window.addEventListener('blur', onScroll)
    return () => {
      window.removeEventListener('mousedown', onDown, true)
      window.removeEventListener('keydown', onKey, true)
      window.removeEventListener('scroll', onScroll, true)
      window.removeEventListener('resize', onScroll)
      window.removeEventListener('blur', onScroll)
    }
  }, [onClose])

  // Roving focus: a menu you opened with the keyboard has to be usable with it.
  const onKeyDown = (e: React.KeyboardEvent): void => {
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return
    e.preventDefault()
    const buttons = Array.from(
      ref.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [],
    )
    if (buttons.length === 0) return
    const at = buttons.findIndex((b) => b === document.activeElement)
    const next = (at + (e.key === 'ArrowDown' ? 1 : -1) + buttons.length) % buttons.length
    buttons[next]?.focus()
  }

  return createPortal(
    <div
      ref={ref}
      className="ctx-menu"
      role="menu"
      style={{ left: pos.x, top: pos.y }}
      onKeyDown={onKeyDown}
      onContextMenu={(e) => e.preventDefault()}
    >
      {props.items.map((item) => (
        <button
          key={item.label}
          type="button"
          role="menuitem"
          className={`ctx-item ${item.danger === true ? 'ctx-danger' : ''}`}
          disabled={item.disabled === true}
          onClick={() => {
            props.onClose()
            item.onClick()
          }}
        >
          {item.label}
        </button>
      ))}
    </div>,
    document.body,
  )
}

export interface UseContextMenu {
  /** Put on the element that should own the menu. */
  onContextMenu: (e: React.MouseEvent) => void
  /** Render inside that element: returns the menu, or null while closed. */
  menu: (items: MenuItem[]) => React.ReactNode
  open: boolean
  close: () => void
}

export function useContextMenu(): UseContextMenu {
  const [pos, setPos] = useState<{ x: number; y: number } | null>(null)

  const onContextMenu = useCallback((e: React.MouseEvent): void => {
    e.preventDefault()
    e.stopPropagation()
    setPos({ x: e.clientX, y: e.clientY })
  }, [])

  const close = useCallback((): void => setPos(null), [])

  const menu = (items: MenuItem[]): React.ReactNode =>
    pos === null ? null : <ContextMenu x={pos.x} y={pos.y} items={items} onClose={close} />

  return { onContextMenu, menu, open: pos !== null, close }
}
