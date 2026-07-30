// Thin typed wrappers for the host/account/alerts surface — request, parse,
// return. No caching here: the daemon already caches what is expensive
// (plan 60s, usage 10min) and lies age better in one place than two.

import { routes } from '../../shared/api/routes'
import {
  parseAccount, parseAlerts, parseAutoswitch, parseClientsInfo, parseLoginSession,
  parseLoginState, parseModelList, parsePing, parsePlan, parseSavedAccounts, parseStatus,
  parseUsage, type Account, type Alerts, type Autoswitch, type ClientsInfo, type LoginSession,
  type LoginState, type ModelChoice, type Plan, type SavedAccount, type Status, type Usage,
} from '../../shared/api/types'
import type { AppdClient } from './client'

export class Host {
  constructor(private readonly client: () => AppdClient) {}

  async ping(): Promise<{ ok: boolean; version: string | null }> {
    const p = parsePing(await this.client().request(routes.ping()))
    return { ok: p.ok, version: p.version }
  }

  async status(): Promise<Status> {
    return parseStatus(await this.client().request(routes.status()))
  }

  async plan(): Promise<Plan> {
    return parsePlan(await this.client().request(routes.plan()))
  }

  async usage(): Promise<Usage> {
    return parseUsage(await this.client().request(routes.usage()))
  }

  async models(): Promise<ModelChoice[]> {
    return parseModelList(await this.client().request(routes.models()))
  }

  async clients(): Promise<ClientsInfo> {
    return parseClientsInfo(await this.client().request(routes.clients()))
  }

  async alertsGet(): Promise<Alerts> {
    return parseAlerts(await this.client().request(routes.alerts()))
  }

  async alertsSet(body: { enabled?: boolean; mode?: string }): Promise<Alerts> {
    return parseAlerts(await this.client().request(routes.alerts(), { method: 'POST', json: body }))
  }

  async account(): Promise<Account> {
    return parseAccount(await this.client().request(routes.account()))
  }

  async savedAccounts(plan: boolean): Promise<SavedAccount[]> {
    return parseSavedAccounts(await this.client().request(routes.accounts(plan)))
  }

  async activateAccount(slug: string): Promise<void> {
    await this.client().request(routes.accountActivate(slug), { method: 'POST', json: {} })
  }

  async forgetAccount(slug: string): Promise<void> {
    await this.client().request(routes.accountForget(slug), { method: 'DELETE' })
  }

  async loginStart(email: string | null): Promise<LoginSession> {
    return parseLoginSession(
      await this.client().request(routes.accountLogin(), {
        method: 'POST',
        json: email === null ? {} : { email },
        tier: 'longPoll',
      }),
    )
  }

  async loginState(): Promise<LoginState> {
    return parseLoginState(await this.client().request(routes.accountLoginState()))
  }

  async loginCode(code: string): Promise<LoginState> {
    return parseLoginState(
      await this.client().request(routes.accountLoginCode(), {
        method: 'POST',
        json: { code },
        tier: 'longPoll',
      }),
    )
  }

  async autoswitch(): Promise<Autoswitch> {
    return parseAutoswitch(await this.client().request(routes.autoswitch()))
  }
}
