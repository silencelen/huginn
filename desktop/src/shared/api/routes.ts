// URL builders for every huginn-appd /v1 route the desktop app uses. Paths and
// query names mirror the Android client (HuginnClient.kt) — the daemon is the
// contract, these are just the spellings.

/** Trim whitespace and trailing slashes so equal URLs compare equal. */
export const normalizeBaseUrl = (url: string): string => url.trim().replace(/\/+$/, '')

/**
 * Session names the daemon accepts. The daemon's own regex allows mixed case
 * but canonicalises to lowercase on create/rename; the client validates the
 * canonical form up front so a rejected name never round-trips.
 */
export const isValidSessionName = (name: string): boolean =>
  /^[a-z0-9_][a-z0-9_.-]{0,49}$/.test(name)

const q = (params: Record<string, string | number | boolean | null | undefined>): string => {
  const parts: string[] = []
  for (const [k, v] of Object.entries(params)) {
    if (v === null || v === undefined || v === false) continue
    parts.push(v === true ? `${k}=1` : `${k}=${encodeURIComponent(String(v))}`)
  }
  return parts.length === 0 ? '' : `?${parts.join('&')}`
}

const seg = encodeURIComponent

export const routes = {
  ping: () => '/v1/ping',
  status: () => '/v1/status',
  alerts: () => '/v1/alerts',
  alertsTest: () => '/v1/alerts/test',
  clients: () => '/v1/clients',
  models: () => '/v1/models',
  autoswitch: () => '/v1/autoswitch',

  watch: (o: { hash?: string | null; waitMs?: number; stream?: boolean } = {}) =>
    `/v1/watch${q({ hash: o.hash ?? undefined, wait: o.waitMs, stream: o.stream ?? false })}`,

  account: () => '/v1/account',
  accountLogin: () => '/v1/account/login',
  accountLoginState: () => '/v1/account/login/state',
  accountLoginCode: () => '/v1/account/login/code',
  accountLogout: () => '/v1/account/logout',
  accounts: (plan = false) => `/v1/accounts${q({ plan })}`,
  accountActivate: (slug: string) => `/v1/accounts/${seg(slug)}/activate`,
  accountForget: (slug: string) => `/v1/accounts/${seg(slug)}`,
  plan: () => '/v1/plan',
  usage: () => '/v1/usage',

  sessions: (preview = false) => `/v1/sessions${q({ preview })}`,
  session: (name: string) => `/v1/sessions/${seg(name)}`,
  sessionRename: (name: string) => `/v1/sessions/${seg(name)}/rename`,
  screen: (
    name: string,
    o: {
      cols?: number
      rows?: number
      history?: number
      hash?: string | null
      waitMs?: number
      force?: boolean
    } = {},
  ) =>
    `/v1/sessions/${seg(name)}/screen${q({
      cols: o.cols,
      rows: o.rows,
      history: o.history,
      hash: o.hash ?? undefined,
      wait: o.waitMs,
      force: o.force ?? false,
    })}`,
  sessionSize: (name: string) => `/v1/sessions/${seg(name)}/size`,
  sessionTranscript: (name: string, o: { offset?: number | null; limit?: number } = {}) =>
    `/v1/sessions/${seg(name)}/transcript${q({ offset: o.offset ?? undefined, limit: o.limit })}`,
  sessionSuggestions: (name: string) => `/v1/sessions/${seg(name)}/suggestions`,
  sessionAgents: (name: string) => `/v1/sessions/${seg(name)}/agents`,
  sessionKeys: (name: string) => `/v1/sessions/${seg(name)}/keys`,
  sessionAnswer: (name: string) => `/v1/sessions/${seg(name)}/answer`,

  chats: () => '/v1/chats',
  chat: (id: string) => `/v1/chats/${seg(id)}`,
  chatMessages: (id: string, stream = false) => `/v1/chats/${seg(id)}/messages${q({ stream })}`,
  chatStream: (id: string, since: number) => `/v1/chats/${seg(id)}/stream${q({ since })}`,
  chatTranscript: (id: string, o: { offset?: number | null; limit?: number } = {}) =>
    `/v1/chats/${seg(id)}/transcript${q({ offset: o.offset ?? undefined, limit: o.limit })}`,
  chatSuggestions: (id: string) => `/v1/chats/${seg(id)}/suggestions`,
  chatCancel: (id: string) => `/v1/chats/${seg(id)}/cancel`,

  uploads: (name?: string | null) => `/v1/uploads${q({ name: name ?? undefined })}`,

  desktopManifest: () => '/v1/desktop/manifest',
  desktopFile: (name: string) => `/v1/desktop/${seg(name)}`,
} as const
