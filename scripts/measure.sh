#!/usr/bin/env bash
# 측정 자동화 (설계 문서 10장). docker compose up 으로 전체가 떠 있는 상태에서 실행한다.
#
#   scripts/measure.sh steady [초]     정상 상태 처리량·지연·중복·유실 (기본 120초)
#   scripts/measure.sh outage [초]     두 공장 회선을 끊었다가 복구 → 복구 시간, 유실 0건 확인 (기본 600초 = 10분)
#
# 필요한 것: bash, curl, docker compose. (jq·python 없이 동작하도록 작성)
set -euo pipefail

SERVER=${SERVER:-http://localhost:8081}     # compose에서 server를 8081로 노출
TOXI=${TOXI:-http://localhost:8474}
PROXIES=(gw-a-uplink gw-b-uplink)
USER_NAME=${USER_NAME:-viewer}
USER_PASSWORD=${USER_PASSWORD:-viewer123}

now_iso() { date -u +"%Y-%m-%d %H:%M:%S+00"; }
log() { echo "[$(date +%H:%M:%S)] $*"; }

login() {
  curl -fsS -X POST "$SERVER/api/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USER_NAME\",\"password\":\"$USER_PASSWORD\"}" | grep -o '"token":"[^"]*"' | cut -d'"' -f4
}

gateways_json() {
  curl -fsS "$SERVER/api/gateways" -H "Authorization: Bearer $TOKEN"
}

# 두 게이트웨이의 bufferedCount 합 (마지막 상태 보고 기준)
buffered_total() {
  gateways_json | grep -o '"bufferedCount":[0-9]*' | cut -d: -f2 | awk '{s+=$1} END {print s+0}'
}

# 기준 시각(epoch ms) 이후에 상태를 보고한 게이트웨이 수.
# 끊긴 동안 서버가 아는 적체 값은 차단 직전 값(거의 0)에서 멈춰 있다. 복구 직후 그 값을 믿으면
# 복구 시간이 0초로 잘못 나오므로, 복구 이후에 새로 보고한 값만 본다.
fresh_reports() {
  gateways_json | grep -o '"lastSeenAt":[0-9]*' | cut -d: -f2 | awk -v t="$1" '$1 > t {n++} END {print n+0}'
}

set_uplinks() { # $1 = true|false
  for p in "${PROXIES[@]}"; do
    curl -fsS -X POST "$TOXI/proxies/$p" -H 'Content-Type: application/json' -d "{\"enabled\":$1}" >/dev/null
  done
}

report() { # $1 from, $2 to
  docker compose exec -T timescaledb psql -U fem -d fem -v from="'$1'" -v to="'$2'" < "$(dirname "$0")/report.sql"
}

TOKEN=$(login)

case "${1:-steady}" in
  steady)
    secs=${2:-120}
    from=$(now_iso); log "정상 상태 측정 ${secs}초"; sleep "$secs"; to=$(now_iso)
    sleep 5 # 마지막 묶음이 저장될 시간
    report "$from" "$to"
    ;;
  outage)
    secs=${2:-600}
    warmup=30
    log "워밍업 ${warmup}초"; sleep $warmup
    from=$(now_iso)
    log "회선 차단 (${PROXIES[*]}) — ${secs}초"; set_uplinks false
    t_cut=$(date +%s)
    sleep "$secs"
    set_uplinks true; t_restore=$(date +%s); to=$(now_iso)
    log "회선 복구. 두 게이트웨이의 재연결 후 첫 보고를 기다린다"
    until [ "$(fresh_reports $((t_restore * 1000)))" -ge ${#PROXIES[@]} ]; do sleep 1; done
    log "재연결·보고 확인 (+$(( $(date +%s) - t_restore ))초), 적체 $(buffered_total)건 → 0이 될 때까지 대기"
    # 상태 보고가 5초 주기라 복구 시간은 최대 5초 오차가 있다
    while [ "$(buffered_total)" -gt 0 ]; do sleep 1; done
    t_drained=$(date +%s)
    log "복구 시간 (회선 복구 → 적체 0): $((t_drained - t_restore))초 / 차단 시간 $((t_restore - t_cut))초"
    sleep 5
    report "$from" "$to"
    ;;
  *)
    echo "usage: $0 steady [seconds] | outage [seconds]"; exit 1 ;;
esac
