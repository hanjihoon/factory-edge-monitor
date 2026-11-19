// 서버 API·WebSocket 응답 모양 (설계 문서 7장). 서버 record 필드 이름과 같다.

export type Role = 'OPERATOR' | 'VIEWER'
export type DeviceStateName = 'RUNNING' | 'STOPPED' | 'FAULT'
export type CommandAction = 'STOP' | 'RESUME'

export interface Session {
  token: string
  username: string
  role: Role
  expiresAt: number
}

export interface Device {
  deviceId: string
  factoryId: string
  seq: number
  deviceTs: number // 측정 시각 (epoch ms)
  state: DeviceStateName
  temperature: number
  vibration: number
  rpm: number
  alarmCode: number
}

export interface Gateway {
  gatewayId: string
  connected: boolean
  lastSeenAt: number
  bufferedCount: number
  oldestBufferedAgeMs: number
  connectedDevices: number
  droppedCount: number
  /** 적체 값이 지금 연결에서 받은 보고인가. false면 끊기기 전 값이라 화면에 숫자를 그대로 보이지 않는다 */
  statusFresh: boolean
  /** 최근 1분 접속 횟수. 5 이상이면 같은 토큰을 쓰는 게이트웨이가 둘 이상인 것으로 본다 (서로 밀어내며 재접속) */
  reconnectsLastMinute: number
}

export interface Command {
  commandId: string
  deviceId: string
  gatewayId: string
  action: CommandAction
  requestedBy: string
  createdAt: number
  status: 'PENDING' | 'SENT' | 'SUCCEEDED' | 'FAILED' | 'TIMED_OUT'
  attempts: number
  completedAt: number
  outcome: string | null
  detail: string | null
}

export interface AuditEntry {
  commandId: string
  at: number
  event: string
  actor: string
  detail: string | null
}

/** /ws/ui 프레임 */
export type LiveFrame =
  | { type: 'snapshot'; devices: Device[]; gateways: Gateway[]; commands: Command[]; serverTime: number }
  | { type: 'devices'; items: Device[]; serverTime: number }
  | { type: 'gateway'; item: Gateway }
  | { type: 'command'; item: Command }

/** GET /api/kpi — 최근 N시간 가동률 (서버 KpiReport). 가동률 = 가동 샘플 ÷ 전체 샘플 */
export interface KpiReport {
  hours: number
  factories: { factoryId: string; samples: number; running: number; stopped: number; fault: number; availabilityPct: number }[]
  hourly: { factoryId: string; hourStartMs: number; samples: number; availabilityPct: number }[]
  worstDevices: { deviceId: string; factoryId: string; samples: number; faultSamples: number; availabilityPct: number }[]
}
