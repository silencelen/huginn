// Wire models for huginn-appd's /v1 API, ported from the Android app's
// Models.kt (the two clients share the daemon contract; the Kotlin file and the
// captured fixtures under mobile/app/src/test/resources are the spec). Every
// field the server may omit is optional/nullable with a parse-time default so
// an older app keeps parsing a newer server.

import {
  asArr, asObj, bool, boolOr, int, intOr, num, numOr, str, strOr, strings,
} from './json'

export interface Ping {
  ok: boolean
  version: string | null
  host: string | null
}

export const parsePing = (v: unknown): Ping => {
  const o = asObj(v)
  return { ok: boolOr(o.ok), version: str(o.version), host: str(o.host) }
}

export interface Disk {
  size: string | null
  used: string | null
  free: string | null
  usedPercent: string | null
}

const parseDisk = (v: unknown): Disk | null => {
  if (v === null || v === undefined) return null
  const o = asObj(v)
  return {
    size: str(o.size), used: str(o.used), free: str(o.free), usedPercent: str(o.usedPercent),
  }
}

export interface Status {
  host: string | null
  appdVersion: string | null
  uptimeSec: number
  load: number[]
  cores: number
  claude: string | null
  mempalace: string | null
  disk: Disk | null
  sessions: number
  chatsRunning: number
}

export const parseStatus = (v: unknown): Status => {
  const o = asObj(v)
  return {
    host: str(o.host),
    appdVersion: str(o.appdVersion),
    uptimeSec: intOr(o.uptimeSec),
    load: asArr(o.load).map((x) => num(x) ?? 0),
    cores: intOr(o.cores),
    claude: str(o.claude),
    mempalace: str(o.mempalace),
    disk: parseDisk(o.disk),
    sessions: intOr(o.sessions),
    chatsRunning: intOr(o.chatsRunning),
  }
}

export interface Session {
  name: string
  createdAt: number
  activityAt: number
  attachedClients: number
  windows: number
  /** running | attention | idle | null (no state recorded yet) */
  state: string | null
  stateSince: number | null
  cols: number
  rows: number
  windowSize: string | null
  sizeLeased: boolean
  claudeSessionId: string | null
  hasTranscript: boolean
  /** Claude Code's own generated session title, far better than the tmux name. */
  title: string | null
  permissionMode: string | null
  /** Last couple of meaningful pane lines: what this session is doing now. */
  preview: string[]
  liveModel: string | null
  liveMode: string | null
  /** Background shells still running, and the longest-running one's command. */
  bgShells: number
  bgAgents: number
  bgTask: string | null
}

export const parseSession = (v: unknown): Session => {
  const o = asObj(v)
  return {
    name: strOr(o.name),
    createdAt: intOr(o.createdAt),
    activityAt: intOr(o.activityAt),
    attachedClients: intOr(o.attachedClients),
    windows: intOr(o.windows),
    state: str(o.state),
    stateSince: int(o.stateSince),
    cols: intOr(o.cols),
    rows: intOr(o.rows),
    windowSize: str(o.windowSize),
    sizeLeased: boolOr(o.sizeLeased),
    claudeSessionId: str(o.claudeSessionId),
    hasTranscript: boolOr(o.hasTranscript),
    title: str(o.title),
    permissionMode: str(o.permissionMode),
    preview: strings(o.preview),
    liveModel: str(o.liveModel),
    liveMode: str(o.liveMode),
    bgShells: intOr(o.bgShells),
    bgAgents: intOr(o.bgAgents),
    bgTask: str(o.bgTask),
  }
}

export const parseSessionList = (v: unknown): Session[] =>
  asArr(asObj(v).sessions).map(parseSession)

/** A Claude Code choice prompt, lifted off the pane so it can become buttons. */
export interface PromptOption {
  number: number
  label: string
  selected: boolean
  /** Multi-select checkbox state; null on rows that are not checkboxes. */
  checked: boolean | null
}

export interface PanePrompt {
  question: string
  options: PromptOption[]
  /** True when the dialog wants a SET of answers rather than one. */
  multiSelect: boolean
  /**
   * Identifies this exact question, computed by the host. An answer carries it
   * back so the host can refuse to type into a pane that has moved on — the app
   * must not compute it itself, because two implementations of "which question
   * is this" would eventually disagree over a space and reject valid answers.
   */
  fingerprint: string | null
}

const parsePanePrompt = (v: unknown): PanePrompt | null => {
  if (v === null || v === undefined) return null
  const o = asObj(v)
  return {
    question: strOr(o.question),
    options: asArr(o.options).map((x) => {
      const p = asObj(x)
      return {
        number: intOr(p.number),
        label: strOr(p.label),
        selected: boolOr(p.selected),
        checked: bool(p.checked),
      }
    }),
    multiSelect: boolOr(o.multiSelect),
    fingerprint: str(o.fingerprint),
  }
}

export interface Screen {
  width: number
  height: number
  cursorX: number
  cursorY: number
  attachedClients: number
  altScreen: boolean
  lines: string[]
  scrollback: string[]
  historySize: number
  windowSize: string | null
  sizeLeased: boolean
  /** True when a resize was refused because another client is attached. */
  resizeBlocked: boolean
  hash: string | null
  /** Set when a long poll expired with no change; `lines` is then empty. */
  unchanged: boolean
  prompt: PanePrompt | null
  /** The live status line ("Gallivanting… · 3m 15s"), the only moment-to-moment signal. */
  spinner: string | null
  /** The TUI's own durable progress rows: workflow phases, "Running N agents". */
  statusLines: string[]
  /** The per-tool row that turns over constantly; updated in place, never stacked. */
  transientLine: string | null
  /** Model/mode as the pane reports them right now (the transcript lags a turn). */
  liveModel: string | null
  liveMode: string | null
  liveBranch: string | null
}

export const parseScreen = (v: unknown): Screen => {
  const o = asObj(v)
  return {
    width: intOr(o.width, 80),
    height: intOr(o.height, 24),
    cursorX: intOr(o.cursorX),
    cursorY: intOr(o.cursorY),
    attachedClients: intOr(o.attachedClients),
    altScreen: boolOr(o.altScreen),
    lines: strings(o.lines),
    scrollback: strings(o.scrollback),
    historySize: intOr(o.historySize),
    windowSize: str(o.windowSize),
    sizeLeased: boolOr(o.sizeLeased),
    resizeBlocked: boolOr(o.resizeBlocked),
    hash: str(o.hash),
    unchanged: boolOr(o.unchanged),
    prompt: parsePanePrompt(o.prompt),
    spinner: str(o.spinner),
    statusLines: strings(o.statusLines),
    transientLine: str(o.transientLine),
    liveModel: str(o.liveModel),
    liveMode: str(o.liveMode),
    liveBranch: str(o.liveBranch),
  }
}

export interface AskQuestion {
  question: string
  header: string | null
  multiSelect: boolean
  options: string[]
}

export interface AskData {
  questions: AskQuestion[]
}

const parseAsk = (v: unknown): AskData | null => {
  if (v === null || v === undefined) return null
  const o = asObj(v)
  return {
    questions: asArr(o.questions).map((x) => {
      const q = asObj(x)
      return {
        question: strOr(q.question),
        header: str(q.header),
        multiSelect: boolOr(q.multiSelect),
        options: strings(q.options),
      }
    }),
  }
}

/**
 * One normalized transcript event. The same shape serves a tmux session and a
 * chat, because both read the same Claude Code transcript.
 *
 * kind: user | assistant | thinking | tool | tool_result | system
 */
export interface TranscriptEvent {
  seq: number
  kind: string
  ts: number | null
  sidechain: boolean
  text: string | null
  name: string | null
  input: string | null
  detail: string | null
  result: string | null
  ok: boolean | null
  /** Typed while Claude was busy: sitting in the queue, not yet delivered. */
  queued: boolean
  /** Present on AskUserQuestion tool events: the structured question card. */
  ask: AskData | null
}

export const parseTranscriptEvent = (v: unknown): TranscriptEvent => {
  const o = asObj(v)
  return {
    seq: intOr(o.seq),
    kind: strOr(o.kind),
    ts: int(o.ts),
    sidechain: boolOr(o.sidechain),
    text: str(o.text),
    name: str(o.name),
    input: str(o.input),
    detail: str(o.detail),
    result: str(o.result),
    ok: bool(o.ok),
    queued: boolOr(o.queued),
    ask: parseAsk(o.ask),
  }
}

/** One background shell: what it runs, and for how long so far. */
export interface BgTask {
  id: string
  command: string
  forSeconds: number
}

/** In-flight work: an unresolved tool call, and how many subagents are busy. */
export interface Activity {
  tool: string | null
  detail: string | null
  sinceTs: number | null
  subagents: number
}

const parseActivity = (v: unknown): Activity | null => {
  if (v === null || v === undefined) return null
  const o = asObj(v)
  return {
    tool: str(o.tool), detail: str(o.detail), sinceTs: int(o.sinceTs),
    subagents: intOr(o.subagents),
  }
}

export interface TranscriptPage {
  events: TranscriptEvent[]
  nextOffset: number
  truncated: boolean
  title: string | null
  permissionMode: string | null
  model: string | null
  gitBranch: string | null
  cwd: string | null
  /** Effort level Claude Code stamped on the last assistant turn. */
  effort: string | null
  /** The model as a person reads it, e.g. `Opus 4.8`, formatted by the server. */
  modelDisplay: string | null
  lastActivityTs: number | null
  state: string | null
  claudeSessionId: string | null
  running: boolean
  mode: string | null
  pending: number
  /** What the transcript tail says is in flight; null when nothing is. */
  activity: Activity | null
  /** Background shells this session still has running. */
  tasks: BgTask[]
  bgAgents: number
}

export const parseTranscriptPage = (v: unknown): TranscriptPage => {
  const o = asObj(v)
  return {
    events: asArr(o.events).map(parseTranscriptEvent),
    nextOffset: intOr(o.nextOffset),
    truncated: boolOr(o.truncated),
    title: str(o.title),
    permissionMode: str(o.permissionMode),
    model: str(o.model),
    gitBranch: str(o.gitBranch),
    cwd: str(o.cwd),
    effort: str(o.effort),
    modelDisplay: str(o.modelDisplay),
    lastActivityTs: int(o.lastActivityTs),
    state: str(o.state),
    claudeSessionId: str(o.claudeSessionId),
    running: boolOr(o.running),
    mode: str(o.mode),
    pending: intOr(o.pending),
    activity: parseActivity(o.activity),
    tasks: asArr(o.tasks).map((x) => {
      const t = asObj(x)
      return { id: strOr(t.id), command: strOr(t.command), forSeconds: intOr(t.forSeconds) }
    }),
    bgAgents: intOr(o.bgAgents),
  }
}

/** Automatic account rotation state, held by the host. */
export interface AutoswitchEvent {
  at: number
  fromEmail: string | null
  fromPercent: number
  fromLabel: string | null
  toEmail: string | null
  toPercent: number
}

export interface Autoswitch {
  enabled: boolean
  switches: number
  last: AutoswitchEvent | null
  accounts: number
}

export const parseAutoswitch = (v: unknown): Autoswitch => {
  const o = asObj(v)
  const l = o.last === null || o.last === undefined ? null : asObj(o.last)
  return {
    enabled: boolOr(o.enabled),
    switches: intOr(o.switches),
    last: l === null ? null : {
      at: intOr(l.at),
      fromEmail: str(l.fromEmail),
      fromPercent: intOr(l.fromPercent),
      fromLabel: str(l.fromLabel),
      toEmail: str(l.toEmail),
      toPercent: intOr(l.toPercent),
    },
    accounts: intOr(o.accounts),
  }
}

/** Suggested next messages, generated at a turn boundary. */
export interface Suggestions {
  suggestions: string[]
  forSize: number
  reason: string | null
}

export const parseSuggestions = (v: unknown): Suggestions => {
  const o = asObj(v)
  return {
    suggestions: strings(o.suggestions),
    forSize: intOr(o.forSize),
    reason: str(o.reason),
  }
}

/** One agent behind a fan-out, live or recently settled. */
export interface AgentRun {
  id: string
  /** The workflow run it belongs to; null for a directly-spawned agent. */
  workflow: string | null
  task: string | null
  lastLine: string | null
  /** The agent's own account of what it concluded, once settled. */
  summary: string | null
  active: boolean
  updatedAt: number
  startedAt: number
  bytes: number
}

export interface AgentsInfo {
  agents: AgentRun[]
  active: number
  serverTime: number
}

export const parseAgentsInfo = (v: unknown): AgentsInfo => {
  const o = asObj(v)
  return {
    agents: asArr(o.agents).map((x) => {
      const a = asObj(x)
      return {
        id: strOr(a.id),
        workflow: str(a.workflow),
        task: str(a.task),
        lastLine: str(a.lastLine),
        summary: str(a.summary),
        active: boolOr(a.active),
        updatedAt: intOr(a.updatedAt),
        startedAt: intOr(a.startedAt),
        bytes: intOr(a.bytes),
      }
    }),
    active: intOr(o.active),
    serverTime: intOr(o.serverTime),
  }
}

export interface Chat {
  id: string
  title: string | null
  mode: string
  model: string | null
  effort: string | null
  createdAt: number
  updatedAt: number
  claudeSessionId: string | null
  lastSnippet: string | null
  turns: number
  running: boolean
  /** Messages waiting for the current run to finish. */
  pending: number
}

export const parseChat = (v: unknown): Chat => {
  const o = asObj(v)
  return {
    id: strOr(o.id),
    title: str(o.title),
    mode: strOr(o.mode, 'ask'),
    model: str(o.model),
    effort: str(o.effort),
    createdAt: intOr(o.createdAt),
    updatedAt: intOr(o.updatedAt),
    claudeSessionId: str(o.claudeSessionId),
    lastSnippet: str(o.lastSnippet),
    turns: intOr(o.turns),
    running: boolOr(o.running),
    pending: intOr(o.pending),
  }
}

export const parseChatList = (v: unknown): Chat[] => asArr(asObj(v).chats).map(parseChat)

/** One persisted digest record. `type` is user | assistant | tool | result | error. */
export interface Message {
  type: string
  text: string | null
  name: string | null
  input: string | null
  ts: number
  partial: boolean
  ok: boolean | null
  durationMs: number | null
  costUsd: number | null
  turns: number | null
}

export const parseMessage = (v: unknown): Message => {
  const o = asObj(v)
  return {
    type: strOr(o.type),
    text: str(o.text),
    name: str(o.name),
    input: str(o.input),
    ts: intOr(o.ts),
    partial: boolOr(o.partial),
    ok: bool(o.ok),
    durationMs: int(o.durationMs),
    costUsd: num(o.costUsd),
    turns: int(o.turns),
  }
}

export interface ChatDetail {
  id: string
  title: string | null
  mode: string
  model: string | null
  effort: string | null
  createdAt: number
  updatedAt: number
  claudeSessionId: string | null
  lastSnippet: string | null
  turns: number
  running: boolean
  messages: Message[]
  partialText: string | null
  /**
   * Where `partialText` ends in the run's event stream, so a reattach can ask
   * for what came AFTER it. Null when no run is in flight, and on daemons older
   * than 2.48.0 — in which case the seed has to be skipped rather than
   * double-counted.
   */
  seq: number | null
}

export const parseChatDetail = (v: unknown): ChatDetail => {
  const o = asObj(v)
  return {
    id: strOr(o.id),
    title: str(o.title),
    mode: strOr(o.mode, 'ask'),
    model: str(o.model),
    effort: str(o.effort),
    createdAt: intOr(o.createdAt),
    updatedAt: intOr(o.updatedAt),
    claudeSessionId: str(o.claudeSessionId),
    lastSnippet: str(o.lastSnippet),
    turns: intOr(o.turns),
    running: boolOr(o.running),
    messages: asArr(o.messages).map(parseMessage),
    partialText: str(o.partialText),
    seq: int(o.seq),
  }
}

export const parseApiError = (v: unknown): string | null => str(asObj(v).error)

/** A decoded SSE frame from a chat run. */
export type ChatEvent =
  | { type: 'started'; chatId: string }
  | { type: 'delta'; text: string }
  | { type: 'assistant'; text: string }
  | { type: 'tool_start'; name: string }
  | { type: 'tool'; name: string; input: string | null }
  | { type: 'result'; ok: boolean; durationMs: number | null; costUsd: number | null }
  | { type: 'error'; text: string }
  | { type: 'done' }

export interface Account {
  loggedIn: boolean
  email: string | null
  orgName: string | null
  subscriptionType: string | null
  authMethod: string | null
  apiProvider: string | null
  error: string | null
}

export const parseAccount = (v: unknown): Account => {
  const o = asObj(v)
  return {
    loggedIn: boolOr(o.loggedIn),
    email: str(o.email),
    orgName: str(o.orgName),
    subscriptionType: str(o.subscriptionType),
    authMethod: str(o.authMethod),
    apiProvider: str(o.apiProvider),
    error: str(o.error),
  }
}

export interface UsageDay {
  date: string | null
  inputTokens: number
  outputTokens: number
  cacheCreationTokens: number
  cacheReadTokens: number
  totalTokens: number
  costUsd: number | null
}

const parseUsageDay = (v: unknown): UsageDay => {
  const o = asObj(v)
  return {
    date: str(o.date),
    inputTokens: intOr(o.inputTokens),
    outputTokens: intOr(o.outputTokens),
    cacheCreationTokens: intOr(o.cacheCreationTokens),
    cacheReadTokens: intOr(o.cacheReadTokens),
    totalTokens: intOr(o.totalTokens),
    costUsd: num(o.costUsd),
  }
}

export interface UsageWindow {
  days: number
  totalTokens: number
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheCreationTokens: number
  costUsd: number | null
}

export interface UsageData {
  today: UsageDay | null
  week: UsageWindow
  daily: UsageDay[]
}

export interface Usage {
  data: UsageData | null
  computedAt: number | null
  stale: boolean
  refreshing: boolean
  error: string | null
  /** ccusage prices at list rates; on a Max plan that overstates the real cost. */
  costIsEstimate: boolean
}

export const parseUsage = (v: unknown): Usage => {
  const o = asObj(v)
  const d = o.data === null || o.data === undefined ? null : asObj(o.data)
  const w = d === null ? {} : asObj(d.week)
  return {
    data: d === null ? null : {
      today: d.today === null || d.today === undefined ? null : parseUsageDay(d.today),
      week: {
        days: intOr(w.days),
        totalTokens: intOr(w.totalTokens),
        inputTokens: intOr(w.inputTokens),
        outputTokens: intOr(w.outputTokens),
        cacheReadTokens: intOr(w.cacheReadTokens),
        cacheCreationTokens: intOr(w.cacheCreationTokens),
        costUsd: num(w.costUsd),
      },
      daily: asArr(d.daily).map(parseUsageDay),
    },
    computedAt: int(o.computedAt),
    stale: boolOr(o.stale),
    refreshing: boolOr(o.refreshing),
    error: str(o.error),
    costIsEstimate: boolOr(o.costIsEstimate, true),
  }
}

export interface LoginSession {
  ok: boolean
  session: string
  existed: boolean
  /** Full sign-in URL, lifted off the pane where it is hard-wrapped. */
  url: string | null
  intendedEmail: string | null
}

export const parseLoginSession = (v: unknown): LoginSession => {
  const o = asObj(v)
  return {
    ok: boolOr(o.ok),
    session: strOr(o.session),
    existed: boolOr(o.existed),
    url: str(o.url),
    intendedEmail: str(o.intendedEmail),
  }
}

/** One row of Claude's plan utilization, as `/usage` shows it. */
export interface PlanLimit {
  kind: string | null
  group: string | null
  label: string
  percent: number
  severity: string
  resetsAt: string | null
  isActive: boolean
}

export interface ExtraUsage {
  utilization: number | null
  usedCredits: number | null
  monthlyLimit: number | null
  currency: string
  spendLimitReached: boolean
}

export interface Plan {
  limits: PlanLimit[]
  extraUsage: ExtraUsage | null
  fetchedAt: number | null
  error: string | null
}

export const parsePlan = (v: unknown): Plan => {
  const o = asObj(v)
  const e = o.extraUsage === null || o.extraUsage === undefined ? null : asObj(o.extraUsage)
  return {
    limits: asArr(o.limits).map((x) => {
      const l = asObj(x)
      return {
        kind: str(l.kind),
        group: str(l.group),
        label: strOr(l.label),
        percent: numOr(l.percent),
        severity: strOr(l.severity, 'normal'),
        resetsAt: str(l.resetsAt),
        isActive: boolOr(l.isActive),
      }
    }),
    extraUsage: e === null ? null : {
      utilization: num(e.utilization),
      usedCredits: num(e.usedCredits),
      monthlyLimit: num(e.monthlyLimit),
      currency: strOr(e.currency, 'USD'),
      spendLimitReached: boolOr(e.spendLimitReached),
    },
    fetchedAt: int(o.fetchedAt),
    error: str(o.error),
  }
}

/** A saved login this host can switch to. */
export interface SavedAccount {
  slug: string
  email: string | null
  orgName: string | null
  savedAt: number | null
  isActive: boolean
  subscriptionType: string | null
  /** Weekly all-models utilization, when it could be read for this account. */
  weeklyPercent: number | null
  sessionPercent: number | null
  /** The email was confirmed from this profile's own token, not inferred. */
  verified: boolean
  /** Another saved profile is the same account, so switching changes nothing. */
  duplicateOf: boolean
}

export const parseSavedAccounts = (v: unknown): SavedAccount[] =>
  asArr(asObj(v).accounts).map((x) => {
    const a = asObj(x)
    return {
      slug: strOr(a.slug),
      email: str(a.email),
      orgName: str(a.orgName),
      savedAt: int(a.savedAt),
      isActive: boolOr(a.isActive),
      subscriptionType: str(a.subscriptionType),
      weeklyPercent: num(a.weeklyPercent),
      sessionPercent: num(a.sessionPercent),
      verified: boolOr(a.verified),
      duplicateOf: boolOr(a.duplicateOf),
    }
  })

/** A model the installed CLI offers, discovered on the host. */
export interface ModelChoice {
  id: string
  display: string
  family: string
}

export const parseModelList = (v: unknown): ModelChoice[] =>
  asArr(asObj(v).models).map((x) => {
    const m = asObj(x)
    return { id: strOr(m.id), display: strOr(m.display), family: strOr(m.family) }
  })

/** Where an uploaded file landed on huginn. */
export interface UploadResult {
  ok: boolean
  path: string
  bytes: number
  ext: string | null
  /**
   * Whether Claude's Read tool can display this, as decided by the host. False
   * for archives, databases, router backups — things a shell can inspect but
   * Read renders as mojibake. It changes what the outgoing message ASKS for.
   */
  readable: boolean
}

export const parseUploadResult = (v: unknown): UploadResult => {
  const o = asObj(v)
  return {
    ok: boolOr(o.ok),
    path: strOr(o.path),
    bytes: intOr(o.bytes),
    ext: str(o.ext),
    readable: boolOr(o.readable, true),
  }
}

/** One chat's state in the watch digest. */
export interface WatchChat {
  running: boolean
  pending: number
  title: string | null
  /**
   * Completed runs, counted rather than flagged. `running` going false is an
   * edge, and anything looking on a schedule can miss one — a chat that started
   * and finished between two observations was never seen running at all. A
   * counter that is higher than last time cannot be missed that way.
   */
  finishedRuns: number
  /** The last thing Claude said, so a finish notification can carry the answer. */
  snippet: string | null
}

/** The change signal a watching client parks on. */
export interface Watch {
  hash: string
  sessions: Record<string, string | null>
  chats: Record<string, WatchChat>
  changed: boolean
  serverTime: number
  /**
   * How many pushes the host believes it has sent this install. NULLABLE on
   * purpose: absent means "this response does not carry the tally", which is
   * not the same as zero. (Meaningless for desktop, kept for contract parity.)
   */
  pushesSent: number | null
}

export const parseWatch = (v: unknown): Watch => {
  const o = asObj(v)
  const sessions: Record<string, string | null> = {}
  for (const [k, val] of Object.entries(asObj(o.sessions))) sessions[k] = str(val)
  const chats: Record<string, WatchChat> = {}
  for (const [k, val] of Object.entries(asObj(o.chats))) {
    const c = asObj(val)
    chats[k] = {
      running: boolOr(c.running),
      pending: intOr(c.pending),
      title: str(c.title),
      finishedRuns: intOr(c.finishedRuns),
      snippet: str(c.snippet),
    }
  }
  return {
    hash: strOr(o.hash),
    sessions,
    chats,
    changed: boolOr(o.changed),
    serverTime: intOr(o.serverTime),
    pushesSent: int(o.pushesSent),
  }
}

/** State of an in-progress sign-in, read off the login session's pane. */
export interface LoginState {
  session: string
  running: boolean
  awaitingCode: boolean
  done: boolean
  url: string | null
  message: string | null
  /** Who the new token actually belongs to, resolved from the token itself. */
  email: string | null
  intendedEmail: string | null
  /** The captured account is one already stored: the same login twice. */
  duplicate: boolean
  /** Signed in as somebody other than the account that was being added. */
  mismatch: boolean
}

export const parseLoginState = (v: unknown): LoginState => {
  const o = asObj(v)
  return {
    session: strOr(o.session, 'login'),
    running: boolOr(o.running),
    awaitingCode: boolOr(o.awaitingCode),
    done: boolOr(o.done),
    url: str(o.url),
    message: str(o.message),
    email: str(o.email),
    intendedEmail: str(o.intendedEmail),
    duplicate: boolOr(o.duplicate),
    mismatch: boolOr(o.mismatch),
  }
}

/** Host-side alerting: reaches a device with the app closed. */
export interface Alerts {
  enabled: boolean
  /**
   * `fallback` — only when no client has checked in recently; `always` — every
   * time. Fallback by default: two notifications for one event teaches you to
   * dismiss both without reading, and then the one that mattered is gone too.
   */
  mode: string
  delivered: number
  lastAt: number | null
  /** telegram | none */
  channel: string
  /** Whether the host currently believes a client is listening. */
  appOnline: boolean
  /** Whether the host holds an FCM credential at all. */
  pushConfigured: boolean
  pushDevices: number
  pushed: number
}

export const parseAlerts = (v: unknown): Alerts => {
  const o = asObj(v)
  return {
    enabled: boolOr(o.enabled),
    mode: strOr(o.mode, 'fallback'),
    delivered: intOr(o.delivered),
    lastAt: int(o.lastAt),
    channel: strOr(o.channel, 'none'),
    appOnline: boolOr(o.appOnline),
    pushConfigured: boolOr(o.pushConfigured),
    pushDevices: intOr(o.pushDevices),
    pushed: intOr(o.pushed),
  }
}

/** One client, as the host has seen it. */
export interface ClientInfo {
  id: string
  /** stream | poll | heartbeat — which mechanism last checked in. */
  kind: string | null
  notify: boolean | null
  lastAt: number
  ageSeconds: number
  checkIns: number
  fresh: boolean
  /** The window this client's own mechanism is judged against. */
  expectedWithinSeconds: number
}

/**
 * The host's own record of who is listening — the evidence that background
 * delivery is working, gathered by a machine that never sleeps.
 */
export interface ClientsInfo {
  clients: ClientInfo[]
  appOnline: boolean
  serverTime: number
}

export const parseClientsInfo = (v: unknown): ClientsInfo => {
  const o = asObj(v)
  return {
    clients: asArr(o.clients).map((x) => {
      const c = asObj(x)
      return {
        id: strOr(c.id),
        kind: str(c.kind),
        notify: bool(c.notify),
        lastAt: intOr(c.lastAt),
        ageSeconds: intOr(c.ageSeconds),
        checkIns: intOr(c.checkIns),
        fresh: boolOr(c.fresh),
        expectedWithinSeconds: intOr(c.expectedWithinSeconds),
      }
    }),
    appOnline: boolOr(o.appOnline),
    serverTime: intOr(o.serverTime),
  }
}

/**
 * The outcome of answering a pane prompt.
 *
 * A refusal is a 409 carrying `reason`: `gone` when there is no question on
 * screen any more, `changed` when the session is asking something else. Both
 * are ordinary — the click was correct when it was offered — so they are
 * reported, never retried.
 */
export interface AnswerResult {
  ok: boolean
  option: number
  label: string | null
  labels: string[] | null
  reason: string | null
  error: string | null
}

export const parseAnswerResult = (v: unknown): AnswerResult => {
  const o = asObj(v)
  return {
    ok: boolOr(o.ok),
    option: intOr(o.option),
    label: str(o.label),
    labels: o.labels === null || o.labels === undefined ? null : strings(o.labels),
    reason: str(o.reason),
    error: str(o.error),
  }
}

/** What arrives on the watching connection. */
export type WatchEvent =
  | { type: 'state'; watch: Watch }
  /** A keepalive. Carries nothing but the fact that the path is still open. */
  | { type: 'alive' }
  /** The server retired a long-lived stream; reconnect at once, do not back off. */
  | { type: 'rotated' }
  | { type: 'failure'; message: string }
