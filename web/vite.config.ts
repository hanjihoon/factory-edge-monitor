import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 개발 서버에서도 운영(nginx)과 같은 주소 구조를 쓴다: /api, /ws 는 서버로 프록시 → CORS 불필요
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': 'http://localhost:8081',
      '/ws': { target: 'ws://localhost:8081', ws: true },
    },
  },
})
