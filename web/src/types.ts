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
