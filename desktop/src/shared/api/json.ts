// Tiny JSON accessors reproducing the kotlinx.serialization posture of the
// Android app's Models.kt: every field the server may omit decodes to a default
// (or null) instead of failing the whole payload. One missing field must not
// take down a response (the devstore lesson).

export type JObj = Record<string, unknown>

export const asObj = (v: unknown): JObj =>
  v !== null && typeof v === 'object' && !Array.isArray(v) ? (v as JObj) : {}

export const asArr = (v: unknown): unknown[] => (Array.isArray(v) ? v : [])

export const str = (v: unknown): string | null => (typeof v === 'string' ? v : null)

export const strOr = (v: unknown, d = ''): string => (typeof v === 'string' ? v : d)

export const num = (v: unknown): number | null =>
  typeof v === 'number' && Number.isFinite(v) ? v : null

export const numOr = (v: unknown, d = 0): number =>
  typeof v === 'number' && Number.isFinite(v) ? v : d

export const int = (v: unknown): number | null => {
  const x = num(v)
  return x === null ? null : Math.trunc(x)
}

export const intOr = (v: unknown, d = 0): number => {
  const x = int(v)
  return x === null ? d : x
}

export const bool = (v: unknown): boolean | null => (typeof v === 'boolean' ? v : null)

export const boolOr = (v: unknown, d = false): boolean => (typeof v === 'boolean' ? v : d)

export const strings = (v: unknown): string[] =>
  asArr(v).filter((x): x is string => typeof x === 'string')
