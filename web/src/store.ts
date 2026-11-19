// 화면 상태와 그 갱신 규칙. React와 분리된 순수 함수라 단위 테스트한다 (store.test.ts).
import type { Command, Device, Gateway, LiveFrame } from './types'

export interface State {
  devices: Map<string, Device>
  gateways: Map<string, Gateway>
  commands: Command[] // 최신순
  /** 최근 측정 → 화면 도착 지연(ms) 표본. 측정 지표(p50, p95) 계산용 */
  latencies: number[]
}

export const MAX_LATENCY_SAMPLES = 5000
const MAX_COMMANDS = 100

export function emptyState(): State {
  return { devices: new Map(), gateways: new Map(), commands: [], latencies: [] }
}

/**
 * 프레임 하나를 반영한 새 상태. now = 브라우저가 프레임을 받은 시각.
 * 서버가 설비 변경을 seq가 클 때만 보내지만, 재연결 직후 스냅샷과 섞일 수 있어 여기서도 seq를 비교한다.
 */
export function apply(state: State, frame: LiveFrame, now: number): State {
  switch (frame.type) {
    case 'snapshot':
      return {
        devices: new Map(frame.devices.map((d) => [d.deviceId, d])),
        gateways: new Map(frame.gateways.map((g) => [g.gatewayId, g])),
        commands: frame.commands.slice(0, MAX_COMMANDS),
        latencies: state.latencies,
      }
    case 'devices': {
      const devices = new Map(state.devices)
      const fresh: number[] = []
      for (const d of frame.items) {
        const old = devices.get(d.deviceId)
        if (!old || d.seq > old.seq) {
          devices.set(d.deviceId, d)
          fresh.push(now - d.deviceTs)
        }
      }
      const latencies = state.latencies.concat(fresh)
      return { ...state, devices, latencies: latencies.slice(-MAX_LATENCY_SAMPLES) }
    }
    case 'gateway': {
      const gateways = new Map(state.gateways)
      gateways.set(frame.item.gatewayId, frame.item)
      return { ...state, gateways }
    }
    case 'command': {
      const others = state.commands.filter((c) => c.commandId !== frame.item.commandId)
      const commands = [frame.item, ...others].sort((a, b) => b.createdAt - a.createdAt).slice(0, MAX_COMMANDS)
      return { ...state, commands }
    }
  }
}

/** 백분위수 (가장 가까운 순위 방식). 표본이 없으면 null */
export function percentile(samples: number[], p: number): number | null {
  if (samples.length === 0) return null
  const sorted = [...samples].sort((a, b) => a - b)
  const rank = Math.ceil((p / 100) * sorted.length)
  return sorted[Math.min(sorted.length, Math.max(1, rank)) - 1]
}

export interface FactorySummary {
  factoryId: string
  total: number
  running: number
  stopped: number
  fault: number
  alarms: number
}

export function summarize(devices: Iterable<Device>): FactorySummary[] {
  const byFactory = new Map<string, FactorySummary>()
  for (const d of devices) {
    const s = byFactory.get(d.factoryId) ?? { factoryId: d.factoryId, total: 0, running: 0, stopped: 0, fault: 0, alarms: 0 }
    s.total++
    if (d.state === 'RUNNING') s.running++
    else if (d.state === 'STOPPED') s.stopped++
    else s.fault++
    if (d.alarmCode !== 0) s.alarms++
    byFactory.set(d.factoryId, s)
  }
  return [...byFactory.values()].sort((a, b) => a.factoryId.localeCompare(b.factoryId))
}

/** 알람 목록: 고장 또는 알람 코드가 있는 설비 */
export function alarms(devices: Iterable<Device>): Device[] {
  return [...devices].filter((d) => d.state === 'FAULT' || d.alarmCode !== 0).sort((a, b) => b.deviceTs - a.deviceTs)
}

/** 같은 명령을 두 번 누르거나 응답 없이 재전송해도 같은 ID → 서버가 한 번만 접수 (멱등) */
export function newCommandId(): string {
  return crypto.randomUUID()
}
