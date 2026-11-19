import { useCallback, useState } from 'react'
import { api } from './api'
import { Dashboard } from './Dashboard'
import { Login } from './Login'
import type { Session } from './types'

const STORAGE_KEY = 'fem.session'

function loadSession(): Session | null {
  try {
    const s = JSON.parse(sessionStorage.getItem(STORAGE_KEY) ?? 'null') as Session | null
    return s && s.expiresAt > Date.now() ? s : null
  } catch {
    return null
  }
}

export function App() {
  const [session, setSession] = useState<Session | null>(loadSession)

  const onLogin = (s: Session) => {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(s)) // 탭을 닫으면 사라진다
    setSession(s)
  }

  const onLogout = useCallback(() => {
    if (session) api.logout(session.token).catch(() => {})
    sessionStorage.removeItem(STORAGE_KEY)
    setSession(null)
  }, [session])

  return session ? <Dashboard session={session} onLogout={onLogout} /> : <Login onLogin={onLogin} />
}
