import { useMemo, useState } from 'react'
import { api, ApiError } from './api'
import { alarms, newCommandId, percentile, summarize } from './store'
import type { AuditEntry, Command, CommandAction, Device, Gateway, Session } from './types'
import { useLive } from './useLive'

const fmtTime = (ms: number) => (ms ? new Date(ms).toLocaleTimeString('ko-KR', { hour12: false }) : '-')
const fmtMs = (v: number | null) => (v == null ? '-' : v < 1000 ? `${Math.round(v)}ms` : `${(v / 1000).toFixed(1)}s`)

export function Dashboard({ session, onLogout }: { session: Session; onLogout: () => void }) {
  const { state, link } = useLive(session.token, onLogout)
  const [selected, setSelected] = useState<string | null>(null)
  const [factory, setFactory] = useState<'ALL' | 'A' | 'B'>('ALL')
  const devices = useMemo(() => [...state.devices.values()], [state.devices])
  const summary = useMemo(() => summarize(devices), [devices])
  const alarmList = useMemo(() => alarms(devices).slice(0, 50), [devices])
  const p50 = percentile(state.latencies, 50)
  const p95 = percentile(state.latencies, 95)

  return (
    <div className="dashboard">
      <header>
        <h1>설비 통합 관제</h1>
        <span className={`link ${link}`}>{link === 'open' ? '실시간 연결됨' : link === 'connecting' ? '연결 중…' : '연결 끊김'}</span>
        <span title="설비 측정 시각 → 이 화면 도착 (최근 5,000건)">종단 지연 p50 {fmtMs(p50)} · p95 {fmtMs(p95)}</span>
        <span className="user">
          {session.username} ({session.role === 'OPERATOR' ? '운영자' : '조회자'})
          <button onClick={onLogout}>로그아웃</button>
        </span>
      </header>

      <section className="row">
        <GatewayPanel gateways={[...state.gateways.values()]} />
        <div className="panel">
          <h2>공장별 현황</h2>
          <table>
            <thead><tr><th>공장</th><th>전체</th><th>가동</th><th>정지</th><th>고장</th><th>알람</th></tr></thead>
            <tbody>
              {summary.map((s) => (
                <tr key={s.factoryId}>
                  <td>{s.factoryId}</td><td>{s.total}</td><td>{s.running}</td><td>{s.stopped}</td>
                  <td className={s.fault ? 'bad' : ''}>{s.fault}</td><td className={s.alarms ? 'bad' : ''}>{s.alarms}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="panel alarms">
          <h2>알람 ({alarms(devices).length})</h2>
          <ul>
            {alarmList.map((d) => (
              <li key={d.deviceId} onClick={() => setSelected(d.deviceId)}>
                <b>{d.deviceId}</b> 코드 {d.alarmCode} · {d.temperature.toFixed(1)}°C · {fmtTime(d.deviceTs)}
              </li>
            ))}
          </ul>
        </div>
      </section>

      <section className="row">
        <div className="panel grow">
          <h2>
            설비 ({devices.length})
            {(['ALL', 'A', 'B'] as const).map((f) => (
              <button key={f} className={factory === f ? 'on' : ''} onClick={() => setFactory(f)}>{f === 'ALL' ? '전체' : `공장 ${f}`}</button>
            ))}
            <span className="legend"><i className="RUNNING" />가동 <i className="STOPPED" />정지 <i className="FAULT" />고장</span>
          </h2>
          <div className="grid">
            {devices.filter((d) => factory === 'ALL' || d.factoryId === factory).map((d) => (
              <div key={d.deviceId} className={`cell ${d.state} ${selected === d.deviceId ? 'sel' : ''}`}
                   title={`${d.deviceId} ${d.state}`} onClick={() => setSelected(d.deviceId)} />
            ))}
          </div>
        </div>
        <div className="side">
          {selected && state.devices.get(selected) && (
            <DeviceDetail device={state.devices.get(selected)!} session={session}
                          commands={state.commands.filter((c) => c.deviceId === selected)} />
          )}
          <CommandList commands={state.commands} token={session.token} />
        </div>
      </section>
    </div>
  )
}

function GatewayPanel({ gateways }: { gateways: Gateway[] }) {
  return (
    <div className="panel">
      <h2>게이트웨이</h2>
      <table>
        <thead><tr><th>ID</th><th>연결</th><th>버퍼 적체</th><th>가장 오래된</th><th>활성 설비</th><th>유실</th></tr></thead>
        <tbody>
          {gateways.map((g) => (
            <tr key={g.gatewayId}>
              <td>{g.gatewayId}</td>
              <td className={g.connected ? 'ok' : 'bad'}>{g.connected ? '연결' : `끊김 (${fmtTime(g.lastSeenAt)}부터)`}</td>
              <td className={g.bufferedCount > 1000 ? 'warn' : ''}>{g.bufferedCount.toLocaleString()}</td>
              <td>{fmtMs(g.oldestBufferedAgeMs)}</td>
              <td>{g.connectedDevices}</td>
              <td className={g.droppedCount ? 'bad' : ''}>{g.droppedCount}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function DeviceDetail({ device, session, commands }: { device: Device; session: Session; commands: Command[] }) {
  const [error, setError] = useState<string | null>(null)
  // 응답을 못 받고 다시 누르면 같은 ID로 다시 보낸다 → 서버가 한 번만 접수 (멱등)
  const [pending, setPending] = useState<{ action: CommandAction; id: string } | null>(null)

  const send = async (action: CommandAction) => {
    const id = pending?.action === action ? pending.id : newCommandId()
    setPending({ action, id })
    setError(null)
    try {
      await api.issueCommand(session.token, id, device.deviceId, action)
      setPending(null)
    } catch (e) {
      setError(e instanceof ApiError ? `요청 실패 (${e.status}) — 다시 누르면 같은 명령으로 재시도합니다` : '서버 응답 없음 — 다시 누르면 같은 명령으로 재시도합니다')
    }
  }

  const canCommand = session.role === 'OPERATOR'
  return (
    <div className="panel">
      <h2>{device.deviceId} <span className={`badge ${device.state}`}>{device.state}</span></h2>
      <dl>
        <dt>온도</dt><dd>{device.temperature.toFixed(1)} °C</dd>
        <dt>진동</dt><dd>{device.vibration.toFixed(2)} mm/s</dd>
        <dt>회전수</dt><dd>{device.rpm} rpm</dd>
        <dt>알람</dt><dd>{device.alarmCode || '-'}</dd>
        <dt>seq</dt><dd>{device.seq}</dd>
        <dt>측정</dt><dd>{fmtTime(device.deviceTs)}</dd>
      </dl>
      <div className="actions">
        <button disabled={!canCommand} onClick={() => send('STOP')}>정지</button>
        <button disabled={!canCommand} onClick={() => send('RESUME')}>재개</button>
        {!canCommand && <small>조회자는 명령할 수 없습니다</small>}
      </div>
      {error && <p className="error">{error}</p>}
      {commands[0] && <p>최근 명령: {commands[0].action} → <b>{commands[0].status}</b> {commands[0].outcome ?? ''}</p>}
    </div>
  )
}

function CommandList({ commands, token }: { commands: Command[]; token: string }) {
  const [audit, setAudit] = useState<{ id: string; entries: AuditEntry[] } | null>(null)
  const open = async (id: string) => setAudit({ id, entries: await api.audit(token, id) })

  return (
    <div className="panel">
      <h2>명령 결과</h2>
      <table className="commands">
        <thead><tr><th>시각</th><th>설비</th><th>동작</th><th>상태</th><th>시도</th><th>요청자</th></tr></thead>
        <tbody>
          {commands.slice(0, 20).map((c) => (
            <tr key={c.commandId} onClick={() => open(c.commandId)} title="클릭하면 감사 로그">
              <td>{fmtTime(c.createdAt)}</td><td>{c.deviceId}</td><td>{c.action}</td>
              <td className={c.status === 'SUCCEEDED' ? 'ok' : c.status === 'FAILED' || c.status === 'TIMED_OUT' ? 'bad' : 'warn'}>
                {c.status}{c.outcome ? ` (${c.outcome})` : ''}
              </td>
              <td>{c.attempts}</td><td>{c.requestedBy}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {audit && (
        <div className="audit">
          <h3>감사 로그 {audit.id.slice(0, 8)}… <button onClick={() => setAudit(null)}>닫기</button></h3>
          <ol>
            {audit.entries.map((a, i) => (
              <li key={i}>{fmtTime(a.at)} <b>{a.event}</b> {a.actor} {a.detail}</li>
            ))}
          </ol>
        </div>
      )}
    </div>
  )
}
