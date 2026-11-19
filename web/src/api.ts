// REST 호출. 같은 주소(nginx)로 요청하므로 CORS가 필요 없다 (설계 결정 12).
import type { AuditEntry, Command, CommandAction, KpiReport, Session } from './types'

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message)
  }
}

async function request<T>(path: string, token: string | null, init: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`
  const res = await fetch(path, { ...init, headers })
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new ApiError(res.status, text || res.statusText)
  }
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T)
}

export const api = {
  login: (username: string, password: string) =>
    request<Session>('/api/login', null, { method: 'POST', body: JSON.stringify({ username, password }) }),

  logout: (token: string) => request<void>('/api/logout', token, { method: 'POST' }),

  /** commandId는 화면이 만든다. 응답이 늦어 다시 눌러도 같은 ID면 서버가 한 번만 접수한다 */
  issueCommand: (token: string, commandId: string, deviceId: string, action: CommandAction) =>
    request<Command>('/api/commands', token, { method: 'POST', body: JSON.stringify({ commandId, deviceId, action }) }),

  kpi: (token: string, hours: number) => request<KpiReport>(`/api/kpi?hours=${hours}`, token),

  audit: (token: string, commandId: string) =>
    request<AuditEntry[]>(`/api/commands/${encodeURIComponent(commandId)}/audit`, token),
}
