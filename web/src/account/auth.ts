import type { SupabaseHttp } from '../sync/supabase-transport.ts'

export interface AuthSession {
  userId: string
  email: string
  accessToken: string
  refreshToken: string
  expiresAtEpochSeconds: number
}

export type AuthState =
  | { kind: 'signed-out' }
  | { kind: 'awaiting-verification'; email: string }
  | { kind: 'signed-in'; session: AuthSession }

export type SignUpResult =
  | { kind: 'signed-in'; session: AuthSession }
  | { kind: 'verification-required'; email: string }

export interface AuthStorage {
  getItem(): string | null
  setItem(value: string): void
  removeItem(): void
}

interface SupabaseWebAuthOptions {
  url: string
  publishableKey: string
  storage: AuthStorage
  fetcher?: typeof fetch
  now?: () => number
}

interface GoTrueSession {
  access_token?: unknown
  refresh_token?: unknown
  expires_at?: unknown
  expires_in?: unknown
  user?: { id?: unknown; email?: unknown } | null
}

const SIGNED_OUT: AuthState = { kind: 'signed-out' }
const EXPIRY_MARGIN_SECONDS = 60

export class SupabaseWebAuth implements SupabaseHttp {
  private readonly baseUrl: string
  private readonly fetcher: typeof fetch
  private readonly now: () => number
  private readonly options: SupabaseWebAuthOptions
  private current: AuthSession | null = null
  private refreshInFlight: Promise<AuthSession> | null = null

  constructor(options: SupabaseWebAuthOptions) {
    this.options = options
    this.baseUrl = options.url.replace(/\/+$/, '')
    this.fetcher = options.fetcher ?? globalThis.fetch.bind(globalThis)
    this.now = options.now ?? (() => Math.floor(Date.now() / 1000))
  }

  async start(): Promise<AuthState> {
    const stored = this.readStoredSession()
    if (stored === null) {
      this.current = null
      return SIGNED_OUT
    }
    this.current = stored
    try {
      return { kind: 'signed-in', session: await this.freshSession() }
    } catch {
      this.clearSession()
      return SIGNED_OUT
    }
  }

  async consumeConfirmation(fragment: string): Promise<AuthState> {
    const parameters = new URLSearchParams(fragment.replace(/^#/, ''))
    const accessToken = parameters.get('access_token')
    const refreshToken = parameters.get('refresh_token')
    const expiresIn = Number(parameters.get('expires_in'))
    if (parameters.get('type') !== 'signup' || accessToken === null || refreshToken === null ||
      accessToken === '' || refreshToken === '' || !Number.isSafeInteger(expiresIn) || expiresIn <= 0) {
      return SIGNED_OUT
    }

    try {
      const user = await this.request('/auth/v1/user', { method: 'GET', accessToken })
      const session = this.toSession({
        access_token: accessToken,
        refresh_token: refreshToken,
        expires_in: expiresIn,
        user,
      })
      if (session === null) return SIGNED_OUT
      this.saveSession(session)
      return { kind: 'signed-in', session }
    } catch {
      return SIGNED_OUT
    }
  }

  async signUp(email: string, password: string): Promise<SignUpResult> {
    const response = await this.request('/auth/v1/signup', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    })
    const session = this.toSession(response)
    if (session === null) {
      this.clearSession()
      return { kind: 'verification-required', email }
    }
    this.saveSession(session)
    return { kind: 'signed-in', session }
  }

  async signIn(email: string, password: string): Promise<AuthSession> {
    const response = await this.request('/auth/v1/token?grant_type=password', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    })
    const session = this.toSession(response)
    if (session === null) throw new Error('A resposta de autenticação não trouxe uma sessão válida')
    this.saveSession(session)
    return session
  }

  async signOut(): Promise<void> {
    const session = this.current ?? this.readStoredSession()
    try {
      if (session !== null) {
        await this.request('/auth/v1/logout', {
          method: 'POST',
          accessToken: session.accessToken,
        })
      }
    } catch {
      // Remote revocation is best-effort; local data and signed-out state stay available.
    } finally {
      this.clearSession()
    }
  }

  async user(): Promise<{ id: string; email: string | null } | null> {
    try {
      const session = await this.freshSession()
      return { id: session.userId, email: session.email }
    } catch {
      return null
    }
  }

  async rpc(fn: string, body: Record<string, unknown>, expectedUserId?: string): Promise<unknown> {
    if (!/^[a-z][a-z0-9_]*$/.test(fn)) throw new TypeError('Nome de RPC inválido')
    const session = await this.freshSession()
    if (expectedUserId !== undefined && session.userId !== expectedUserId) {
      throw new Error('A conta mudou antes da sincronização')
    }
    return this.request(`/rest/v1/rpc/${fn}`, {
      method: 'POST',
      accessToken: session.accessToken,
      body: JSON.stringify(body),
    })
  }

  private async freshSession(): Promise<AuthSession> {
    const session = this.current ?? this.readStoredSession()
    if (session === null) throw new Error('Sessão necessária para sincronizar')
    this.current = session
    if (session.expiresAtEpochSeconds > this.now() + EXPIRY_MARGIN_SECONDS) return session
    if (this.refreshInFlight !== null) return this.refreshInFlight

    const refresh = this.refresh(session.refreshToken)
      .then((refreshed) => {
        this.saveSession(refreshed)
        return refreshed
      })
      .catch((error: unknown) => {
        this.clearSession()
        throw error
      })
      .finally(() => { this.refreshInFlight = null })
    this.refreshInFlight = refresh
    return refresh
  }

  private async refresh(refreshToken: string): Promise<AuthSession> {
    const response = await this.request('/auth/v1/token?grant_type=refresh_token', {
      method: 'POST',
      body: JSON.stringify({ refresh_token: refreshToken }),
    })
    const session = this.toSession(response)
    if (session === null) throw new Error('A renovação não trouxe uma sessão válida')
    return session
  }

  private async request(path: string, input: { method: string; body?: string; accessToken?: string }): Promise<unknown> {
    let response: Response
    try {
      response = await this.fetcher(`${this.baseUrl}${path}`, {
        method: input.method,
        headers: {
          apikey: this.options.publishableKey,
          ...(input.accessToken === undefined ? {} : { authorization: `Bearer ${input.accessToken}` }),
          ...(input.body === undefined ? {} : { 'content-type': 'application/json' }),
        },
        ...(input.body === undefined ? {} : { body: input.body }),
      })
    } catch {
      throw new Error('Não foi possível conectar ao Supabase')
    }
    const body = await response.json().catch(() => null) as unknown
    if (!response.ok) throw new Error(authMessage(body, response.status))
    return body
  }

  private toSession(value: unknown): AuthSession | null {
    if (typeof value !== 'object' || value === null) return null
    const body = value as GoTrueSession
    const id = body.user?.id
    const email = body.user?.email
    const accessToken = body.access_token
    const refreshToken = body.refresh_token
    if (typeof id !== 'string' || typeof email !== 'string' || typeof accessToken !== 'string' || typeof refreshToken !== 'string') return null
    const expiresAt = typeof body.expires_at === 'number'
      ? body.expires_at
      : this.now() + (typeof body.expires_in === 'number' ? body.expires_in : 3600)
    if (!Number.isSafeInteger(expiresAt) || expiresAt <= this.now()) return null
    return { userId: id, email, accessToken, refreshToken, expiresAtEpochSeconds: expiresAt }
  }

  private readStoredSession(): AuthSession | null {
    try {
      const value: unknown = JSON.parse(this.options.storage.getItem() ?? 'null')
      if (typeof value !== 'object' || value === null) return null
      const session = value as Partial<AuthSession>
      if (typeof session.userId !== 'string' || typeof session.email !== 'string' ||
        typeof session.accessToken !== 'string' || typeof session.refreshToken !== 'string' ||
        typeof session.expiresAtEpochSeconds !== 'number' || !Number.isSafeInteger(session.expiresAtEpochSeconds)) return null
      return session as AuthSession
    } catch {
      return null
    }
  }

  private saveSession(session: AuthSession): void {
    this.current = session
    this.options.storage.setItem(JSON.stringify(session))
  }

  private clearSession(): void {
    this.current = null
    this.options.storage.removeItem()
  }
}

export function browserAuthStorage(): AuthStorage {
  const key = 'tcg-colecao-auth-session'
  return {
    getItem: () => window.sessionStorage.getItem(key),
    setItem: (value) => window.sessionStorage.setItem(key, value),
    removeItem: () => window.sessionStorage.removeItem(key),
  }
}

function authMessage(value: unknown, status: number): string {
  const message = typeof value === 'object' && value !== null && 'message' in value && typeof value.message === 'string'
    ? value.message
    : ''
  if (/invalid login credentials|email not confirmed/i.test(message)) return 'E-mail ou senha inválidos, ou e-mail ainda não confirmado.'
  if (/already registered|user already exists/i.test(message)) return 'Não foi possível criar a conta com esses dados. Tente entrar ou use outro e-mail.'
  if (status >= 500) return 'O serviço de conta está indisponível. Tente novamente mais tarde.'
  return 'Não foi possível concluir a solicitação. Confira os dados e tente novamente.'
}
