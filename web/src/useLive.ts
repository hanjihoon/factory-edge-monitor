// /ws/ui 연결을 유지하고 받은 프레임을 상태에 반영하는 훅.
import { useEffect, useRef, useState } from 'react'
import { apply, emptyState, type State } from './store'
import type { LiveFrame } from './types'

export type LinkState = 'connecting' | 'open' | 'closed'

/**
 * 끊기면 1초 → 2초 → … 최대 10초 간격으로 다시 붙는다. 다시 붙으면 서버가 스냅샷을 보내므로
 * 끊긴 동안 놓친 변경을 따로 챙길 필요가 없다.
 *
 * 성능: 프레임마다 setState 하면 초당 4번(서버 0.25초 묶음) 다시 그린다. 1,000칸 그리드도 이 정도는 감당한다.
 */
export function useLive(token: string | null, onUnauthorized: () => void) {
  const [state, setState] = useState<State>(emptyState)
  const [link, setLink] = useState<LinkState>('connecting')
  const unauthorized = useRef(onUnauthorized)
  unauthorized.current = onUnauthorized

  useEffect(() => {
    if (!token) return
    let ws: WebSocket | null = null
    let retry = 0
    let timer: number | undefined
    let stopped = false

    const connect = () => {
      setLink('connecting')
      const proto = location.protocol === 'https:' ? 'wss' : 'ws'
      // 브라우저 WebSocket은 헤더를 못 붙여 토큰을 쿼리로 보낸다 (설계 문서 12장 한계)
      ws = new WebSocket(`${proto}://${location.host}/ws/ui?token=${encodeURIComponent(token)}`)
      let opened = false
      ws.onopen = () => {
        opened = true
        retry = 0
        setLink('open')
      }
      ws.onmessage = (e) => {
        const frame = JSON.parse(e.data) as LiveFrame
        const now = Date.now()
        setState((s) => apply(s, frame, now))
      }
      ws.onclose = () => {
        setLink('closed')
        if (stopped) return
        // 핸드셰이크 자체가 실패하면(열리기 전에 닫힘) 토큰 만료일 수 있다. 세 번 연속이면 로그인으로 돌려보낸다
        if (!opened && ++retry >= 3) {
          unauthorized.current()
          return
        }
        timer = window.setTimeout(connect, Math.min(10_000, 1000 * 2 ** Math.min(retry, 4)))
      }
    }
    connect()
    return () => {
      stopped = true
      window.clearTimeout(timer)
      ws?.close()
    }
  }, [token])

  return { state, link }
}
