# 관제 UI: node로 빌드 → nginx가 정적 파일 제공 + /api, /ws 를 서버로 프록시 (설계 결정 12)
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build && npx vitest run

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
