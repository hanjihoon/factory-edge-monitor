#!/usr/bin/env bash
# 측정 자동화 (설계 문서 10장). docker compose up 으로 전체가 떠 있는 상태에서 실행한다.
#
#   scripts/measure.sh steady [초]     정상 상태 처리량·지연·중복·유실 (기본 120초)
#   scripts/measure.sh outage [초]     두 공장 회선을 끊었다가 복구 → 복구 시간, 유실 0건 확인 (기본 600초 = 10분)
#   scripts/measure.sh fault <서비스>[,<서비스>] [초]  구성 요소를 강제 종료(kill -9)했다 다시 띄움 → 회복 시간, 유실·중복 (기본 30초)
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
  fault)
    # 회선이 아닌 구성 요소를 강제 종료(SIGKILL)했다가 다시 띄운다. 프로세스를 죽이는 것이지 전원을 끊는 것은 아니다
    # (호스트 운영체제는 살아 있어 디스크 캐시가 남는다. 진짜 전원 차단 시험은 하지 않았다 → 설계 문서 12장).
    #   gateway-a  MQTT 게이트웨이. 디스크 커밋 후 PUBACK + 브로커 영속 세션. 정지 허용 시간(브로커 대기열) 안이면 유실 0
    #   gateway-b  Modbus 게이트웨이. 꺼진 동안은 아무도 폴링하지 않으므로 그 사이 샘플은 수집 단계에서 빠진다 (예상된 한계)
    #   server     커밋 전 묶음은 ack가 안 나갔으므로 재전송 → 유실 0, 중복 0이어야 한다
    #   timescaledb  서버가 저장에 실패하면 ack를 안 보낸다 → 게이트웨이에 쌓였다가 재전송
    #   mosquitto  공장 A 브로커. 꺼진 동안 설비(에뮬레이터)의 발행이 실패한다 → 설비 쪽 손실 (설비가 버퍼를 갖는지에 달림)
    #   gateway-a,mosquitto  정전 흉내: 둘을 동시에 죽인다. 브로커 메모리에 있던 "설비에는 받았다고 했지만
    #                        게이트웨이가 아직 가져가지 않은" 메시지의 양을 추정한다
    svcs=${2:?"usage: $0 fault <service>[,<service>...] [down_seconds]"}
    down=${3:-30}
    IFS=',' read -r -a svc_list <<< "$svcs"
    warmup=20
    log "워밍업 ${warmup}초"; sleep $warmup
    from=$(now_iso)
    sleep 5
    log "${svc_list[*]} 강제 종료 (SIGKILL) — ${down}초 뒤 다시 띄운다"
    t_kill_ms=$(date +%s%3N)
    docker compose kill -s KILL "${svc_list[@]}" >/dev/null 2>&1
    sleep "$down"
    docker compose start "${svc_list[@]}" >/dev/null 2>&1; t_start=$(date +%s)
    log "기동. 모든 게이트웨이가 새 상태 보고에서 적체 해소(가장 오래된 미확인 < ${DRAINED_AGE_MS}ms)를 보고할 때까지 잰다"
    sleep 10
    deadline=$(( t_start + 600 ))
    while :; do
      [ "$(date +%s)" -lt "$deadline" ] || { log "10분 안에 회복하지 않음"; break; }
      # 서버를 재시작하면 사용자 세션(메모리)이 사라지므로 매번 다시 로그인한다
      TOKEN=$(login 2>/dev/null) || { sleep 1; continue; }
      rows=$(gateways_json 2>/dev/null | tr '}' '\n' | grep -o '"gatewayId":"[^"]*"\|"oldestBufferedAgeMs":-\?[0-9]*\|"statusFresh":[a-z]*' | tr '\n' ' ' || true)
      # ↑ 서버가 아직 안 떴으면 curl이 실패한다. pipefail 때문에 스크립트 전체가 끝나지 않도록 빈 값으로 둔다
      n_ok=$(echo "$rows" | grep -o '"gatewayId"[^"]*"[^"]*" "oldestBufferedAgeMs":[0-9]* "statusFresh":true' \
             | awk -v lim="$DRAINED_AGE_MS" '{split($2, a, ":"); if (a[2] + 0 < lim) n++} END {print n + 0}' || true)
      # ↑ 서버 재시작 직후에는 새 상태 보고가 하나도 없어 grep이 실패한다 (같은 이유로 || true)
      [ "$n_ok" -ge 2 ] && break
      sleep 1
    done
    log "회복 시간 (기동 → 두 게이트웨이 적체 해소): $(( $(date +%s) - t_start ))초 / 중단 ${down}초"
    # 기동 뒤 30초까지 측정된 데이터를 포함해야 재기동 경계의 빈 번호를 잡는다
    end=$(( t_start + 30 )); now=$(date +%s); [ "$now" -ge $(( end + 10 )) ] || sleep $(( end + 10 - now ))
    to=$(date -u -d "@$end" +"%Y-%m-%d %H:%M:%S+00")
    # 게이트웨이 적체 해소만 보고 바로 집계하면 안 된다. 게이트웨이를 다시 띄우면 브로커에 쌓여 있던 메시지가
    # 그때부터 넘어오는데, 게이트웨이 디스크 버퍼는 금방 비어서 "해소"로 보인다. 이때 집계하면 아직 안 온
    # 최신 메시지가 빠져 빈 번호가 안 보이고 유실 0으로 잘못 나온다 (게이트웨이 200초 정지에서 실제로 그랬다).
    # 그래서 구간 저장 건수가 10초 동안 늘지 않을 때까지 기다린다.
    prev=-1
    for _ in $(seq 1 120); do
      cur=$(docker compose exec -T timescaledb psql -U fem -d fem -Atc \
            "SELECT count(*) FROM telemetry WHERE device_ts BETWEEN '$from' AND '$to'" 2>/dev/null || echo -1)
      [ "$cur" = "$prev" ] && break
      prev=$cur; sleep 10
    done
    log "구간 저장 건수 ${cur}건에서 멈춤 → 집계"
    report "$from" "$to" | sed -n '/== 3/,$p'
    case ",$svcs," in *,mosquitto,*)
      # 설비는 1초마다 seq를 하나씩 올려 발행한다. 설비마다 "강제 종료 전 마지막으로 저장된 레코드"부터 종료 시각까지
      # 몇 초가 비었는지 보면, 종료 직전에 발행됐지만 저장되지 못한 건수를 추정할 수 있다.
      # 이것이 정전 때 브로커 메모리(또는 게이트웨이로 가는 중)에서 사라지는 양이다. 지연 주입(0~300ms) 때문에 ±1건 오차
      echo "== 6. 종료 직전에 발행됐지만 저장되지 못한 건수 추정 (공장 A, 브로커 메모리 구간)"
      docker compose exec -T timescaledb psql -U fem -d fem -v kill="$t_kill_ms" <<'SQL'
WITH last_before AS (
    SELECT device_id, max(device_ts) AS ts
    FROM telemetry
    WHERE factory_id = 'A' AND device_ts <= to_timestamp(:kill / 1000.0)
      AND device_ts > to_timestamp(:kill / 1000.0) - INTERVAL '2 minutes'
    GROUP BY device_id
), per_device AS (
    SELECT floor(extract(epoch FROM to_timestamp(:kill / 1000.0) - ts))::int AS lost FROM last_before
)
SELECT count(*) AS devices, sum(lost) AS est_lost_before_kill,
       round(avg(lost), 2) AS avg_per_device, max(lost) AS max_per_device
FROM per_device;
SQL
      ;;
    esac
    ;;
  *)
    echo "usage: $0 steady [seconds] | outage [seconds] | fault <service>[,<service>...] [down_seconds]"; exit 1 ;;
esac
