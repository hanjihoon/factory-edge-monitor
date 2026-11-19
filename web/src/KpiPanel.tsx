// 가동률 KPI (선택 구현). 서버가 TimescaleDB 연속 집계(1분 단위)로 미리 세어 둔 값을 1분마다 다시 읽는다.
// 실시간 상태(WebSocket)와 달리 "지난 N시간 동안 얼마나 돌았나"를 본다.
import { useEffect, useState } from 'react'
import { api } from './api'
import type { KpiReport } from './types'

const REFRESH_MS = 60_000

export function KpiPanel({ token, onSelect }: { token: string; onSelect: (deviceId: string) => void }) {
  const [hours, setHours] = useState(24)
  const [report, setReport] = useState<KpiReport | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let alive = true
    const load = () =>
      api.kpi(token, hours)
        .then((r) => { if (alive) { setReport(r); setError(null) } })
        .catch((e) => { if (alive) setError(String(e.message ?? e)) })
    load()
    const timer = setInterval(load, REFRESH_MS)
    return () => { alive = false; clearInterval(timer) }
  }, [token, hours])

  return (
    <div className="panel kpi">
      <h2>
        가동률
        {[1, 24, 168].map((h) => (
          <button key={h} className={hours === h ? 'on' : ''} onClick={() => setHours(h)}>{h === 168 ? '7일' : `${h}시간`}</button>
        ))}
      </h2>
      {error && <p className="error">{error}</p>}
      {report && report.factories.length === 0 && <p className="muted">집계할 데이터가 아직 없다</p>}
      {report?.factories.map((f) => (
        <div key={f.factoryId} className="kpi-factory">
          <div className="kpi-head">
            <b>공장 {f.factoryId}</b>
            <span className="kpi-pct">{f.availabilityPct.toFixed(1)}%</span>
            <span className="muted">가동 {pct(f.running, f.samples)} · 정지 {pct(f.stopped, f.samples)} · 고장 {pct(f.fault, f.samples)}</span>
          </div>
          {/* 시간대별 막대. 높이 = 그 시간의 가동률 */}
          <div className="kpi-bars" title="시간대별 가동률">
            {report.hourly.filter((p) => p.factoryId === f.factoryId).map((p) => (
              <i key={p.hourStartMs} style={{ height: `${Math.max(p.availabilityPct, 2)}%` }}
                 title={`${new Date(p.hourStartMs).toLocaleString('ko-KR', { hour12: false })} ${p.availabilityPct}%`} />
            ))}
          </div>
        </div>
      ))}
      {report && report.worstDevices.length > 0 && (
        <>
          <h3>가동률 낮은 설비</h3>
          <table>
            <tbody>
              {report.worstDevices.map((d) => (
                <tr key={d.deviceId} onClick={() => onSelect(d.deviceId)} className="clickable">
                  <td>{d.deviceId}</td><td>{d.availabilityPct.toFixed(1)}%</td>
                  <td className={d.faultSamples ? 'bad' : ''}>고장 {fmtDuration(d.faultSamples)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </div>
  )
}

const pct = (n: number, total: number) => (total ? `${((100 * n) / total).toFixed(1)}%` : '-')

/** 샘플 1건 = 1초 */
const fmtDuration = (sec: number) => (sec < 60 ? `${sec}초` : sec < 3600 ? `${Math.round(sec / 60)}분` : `${(sec / 3600).toFixed(1)}시간`)
