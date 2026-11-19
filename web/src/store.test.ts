import { describe, expect, it } from 'vitest'
import { alarms, apply, emptyState, percentile, summarize } from './store'
import type { Command, Device } from './types'

const dev = (id: string, seq: number, extra: Partial<Device> = {}): Device => ({
  deviceId: id, factoryId: id[0], seq, deviceTs: 1000, state: 'RUNNING',
  temperature: 60, vibration: 2, rpm: 1500, alarmCode: 0, ...extra,
})

const cmd = (id: string, createdAt: number, status: Command['status'] = 'SENT'): Command => ({
  commandId: id, deviceId: 'A-0001', gatewayId: 'gw-a', action: 'STOP', requestedBy: 'operator',
  createdAt, status, attempts: 1, completedAt: 0, outcome: null, detail: null,
})

describe('apply', () => {
  it('snapshot replaces everything', () => {
    const s = apply(emptyState(), { type: 'snapshot', devices: [dev('A-0001', 1)], gateways: [], commands: [], serverTime: 0 }, 0)
    expect(s.devices.size).toBe(1)
  })

  it('device updates keep only newer seq and record latency', () => {
    let s = apply(emptyState(), { type: 'devices', items: [dev('A-0001', 5)], serverTime: 0 }, 1300)
    s = apply(s, { type: 'devices', items: [dev('A-0001', 4, { state: 'FAULT' })], serverTime: 0 }, 1400)

    expect(s.devices.get('A-0001')!.seq).toBe(5)
    expect(s.devices.get('A-0001')!.state).toBe('RUNNING')
    expect(s.latencies).toEqual([300]) // 무시된 옛 값은 지연 표본에도 넣지 않는다
  })

  it('command updates replace by id and stay newest first', () => {
    let s = apply(emptyState(), { type: 'command', item: cmd('c-1', 1) }, 0)
    s = apply(s, { type: 'command', item: cmd('c-2', 2) }, 0)
    s = apply(s, { type: 'command', item: cmd('c-1', 1, 'SUCCEEDED') }, 0)

    expect(s.commands.map((c) => c.commandId)).toEqual(['c-2', 'c-1'])
    expect(s.commands[1].status).toBe('SUCCEEDED')
  })
})

describe('percentile', () => {
  it('uses nearest rank', () => {
    const samples = Array.from({ length: 100 }, (_, i) => i + 1) // 1..100
    expect(percentile(samples, 50)).toBe(50)
    expect(percentile(samples, 95)).toBe(95)
    expect(percentile([7], 95)).toBe(7)
    expect(percentile([], 50)).toBeNull()
  })
})

describe('summaries', () => {
  it('counts states and alarms per factory', () => {
    const devices = [dev('A-0001', 1), dev('A-0002', 1, { state: 'FAULT', alarmCode: 101 }), dev('B-0001', 1, { state: 'STOPPED' })]

    expect(summarize(devices)).toEqual([
      { factoryId: 'A', total: 2, running: 1, stopped: 0, fault: 1, alarms: 1 },
      { factoryId: 'B', total: 1, running: 0, stopped: 1, fault: 0, alarms: 0 },
    ])
    expect(alarms(devices).map((d) => d.deviceId)).toEqual(['A-0002'])
  })
})
