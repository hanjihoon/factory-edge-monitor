import { useState, type FormEvent } from 'react'
import { api, ApiError } from './api'
import type { Session } from './types'

export function Login({ onLogin }: { onLogin: (s: Session) => void }) {
  const [username, setUsername] = useState('operator')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      onLogin(await api.login(username, password))
    } catch (err) {
      setError(err instanceof ApiError && err.status === 401 ? '아이디 또는 비밀번호가 틀렸습니다' : '서버에 연결할 수 없습니다')
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="login" onSubmit={submit}>
      <h1>설비 통합 관제</h1>
      <label>
        계정
        <select value={username} onChange={(e) => setUsername(e.target.value)}>
          <option value="operator">operator (운영자: 조회 + 명령)</option>
          <option value="viewer">viewer (조회자: 조회만)</option>
        </select>
      </label>
      <label>
        비밀번호
        <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoFocus />
      </label>
      {error && <p className="error">{error}</p>}
      <button disabled={busy || !password}>로그인</button>
    </form>
  )
}
