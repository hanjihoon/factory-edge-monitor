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

# 게이트웨이별 "gatewayId 가장오래된미확인나이(ms) 미확인건수" 한 줄씩 (마지막 상태 보고 기준).
# jq 없이 파싱하려고 JSON 배열을 객체('}')마다 줄로 나눈다.
gateway_rows() {
  gateways_json | tr '}' '\n' | while read -r obj; do
    id=$(echo "$obj" | grep -o '"gatewayId":"[^"]*"' | cut -d'"' -f4)
    [ -n "$id" ] || continue
    age=$(echo "$obj" | grep -o '"oldestBufferedAgeMs":-\?[0-9]*' | cut -d: -f2)
    cnt=$(echo "$obj" | grep -o '"bufferedCount":[0-9]*' | cut -d: -f2)
    echo "$id $age $cnt"
  done
}

# 복구 판정 기준: 가장 오래된 미확인 레코드가 이 값보다 젊으면 "적체 해소"로 본다.
# 건수 0을 기준으로 삼지 않는 이유: 정상 상태에서도 전송 중·ack 대기 중인 수십~수백 건이
# 늘 있어서, 0이 찍히는 순간은 우연에 가깝다 (첫 측정에서 실제 해소 후 1분 넘게 더 기다렸다).
DRAINED_AGE_MS=${DRAINED_AGE_MS:-5000}

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
    log "회선 복구. 게이트웨이별로 적체 보고 → 해소(가장 오래된 미확인 < ${DRAINED_AGE_MS}ms)까지 잰다"
    # 게이트웨이마다 두 단계를 거친다.
    #  1) seen: 복구 뒤 적체(나이 > 기준)를 보고한 것을 먼저 확인한다. 재연결 직후 서버에 남아 있는 값은
    #     차단 직전 보고(적체 없음)라서, 이 단계 없이 보면 복구 시간이 0초로 잘못 나온다.
    #  2) done: 그 다음 나이가 기준 아래로 내려온 시각을 복구 완료로 기록한다.
    # 상태 보고가 5초 주기라 복구 시간은 최대 5초 오차가 있다.
    # macOS 기본 bash 3.2에는 연관 배열이 없어서, 상태를 " gw-a gw-b " 같은 공백 구분 문자열로 들고 있다.
    seen=" "; done_ids=" "; n_done=0; slowest=0
    deadline=$(( t_restore + 1800 )) # 30분 안에 해소되지 않으면 실패로 끝낸다 (무한 대기 방지)
    while [ "$n_done" -lt ${#PROXIES[@]} ]; do
      [ "$(date +%s)" -lt "$deadline" ] || { log "30분 안에 적체가 해소되지 않음"; exit 1; }
      while read -r id age cnt; do
        case "$done_ids" in *" $id "*) continue ;; esac
        case "$seen" in
          *" $id "*)
            if [ "$age" -lt "$DRAINED_AGE_MS" ]; then
              elapsed=$(( $(date +%s) - t_restore ))
              done_ids="$done_ids$id "; n_done=$((n_done + 1)); [ "$elapsed" -gt "$slowest" ] && slowest=$elapsed
              log "$id 적체 해소: 회선 복구 후 ${elapsed}초 (남은 미확인 ${cnt}건)"
            fi ;;
          *)
            if [ "$age" -gt "$DRAINED_AGE_MS" ]; then
              seen="$seen$id "; log "$id 적체 보고: ${cnt}건, 가장 오래된 것 $((age / 1000))초"
            fi ;;
        esac
      done < <(gateway_rows)
      sleep 1
    done
    log "복구 시간 (회선 복구 → 모든 게이트웨이 적체 해소): ${slowest}초 / 차단 시간 $((t_restore - t_cut))초"
    sleep 5
    report "$from" "$to"
    # 복구 직후 180초 동안 측정된 데이터가 다 저장될 때까지 기다린 뒤 실시간 회복을 본다
    wait_until=$(( t_restore + 190 )); now=$(date +%s)
    [ "$now" -ge "$wait_until" ] || sleep $(( wait_until - now ))
    docker compose exec -T timescaledb psql -U fem -d fem -v restore="'$to'" < "$(dirname "$0")/recovery.sql"
    ;;
  *)
    echo "usage: $0 steady [seconds] | outage [seconds]"; exit 1 ;;
esac
