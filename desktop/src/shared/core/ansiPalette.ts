// The 256-entry SGR colour palette, ported from the Android app's ui/Ansi.kt
// (the mobile app is the reference implementation; its palette values carry
// over verbatim). Renderer-agnostic: colours are plain RGBA records, not any
// UI framework's colour type. The Kotlin file once held an ANSI-to-string
// renderer too; the cell grid replaced it, so only the palette remains.

export type TermColor = {
  readonly r: number
  readonly g: number
  readonly b: number
  /** 0-255. Dim text carries a reduced alpha (see terminalGrid). */
  readonly a: number
}

export const rgb = (r: number, g: number, b: number, a = 255): TermColor => ({ r, g, b, a })

/** Compose-style 0xAARRGGBB literal → TermColor, so the Kotlin values read identically. */
export const argb = (v: number): TermColor =>
  rgb((v >>> 16) & 0xff, (v >>> 8) & 0xff, v & 0xff, (v >>> 24) & 0xff)

export const palette: readonly TermColor[] = (() => {
  const out: TermColor[] = []
  // 0-15: standard + bright, tuned to read on the app's near-black surface
  // rather than matching a specific terminal's defaults exactly.
  for (const v of [
    0xff3b3733, 0xffd1544f, 0xff69a95b, 0xffc7a24a,
    0xff5b8fd6, 0xffa974c4, 0xff4fa5a8, 0xffcfc8bf,
    0xff6b645d, 0xffe8736d, 0xff8ccb7b, 0xffe3c169,
    0xff7dafea, 0xffc495dc, 0xff6fc4c7, 0xfff2ece4,
  ]) {
    out.push(argb(v))
  }
  // 16-231: the 6x6x6 cube
  const steps = [0, 95, 135, 175, 215, 255]
  for (const r of steps) for (const g of steps) for (const b of steps) out.push(rgb(r, g, b))
  // 232-255: greyscale ramp
  for (let i = 0; i < 24; i++) {
    const v = 8 + i * 10
    out.push(rgb(v, v, v))
  }
  return out
})()
