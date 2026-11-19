#!/usr/bin/env bash
# 원격 명령 왕복을 실제 compose 환경에서 확인한다 (설계 문서 8장 "원격 명령", 10장).
#
#   관제 UI와 같은 경로(nginx :8080 → server)로 REST 요청 → 서버 → 게이트웨이(WebSocket) → 설비(MQTT/Modbus)
#   → 결과 ack → 명령 상태·감사 로그 → 설비가 보내는 텔레메트리의 상태가 실제로 바뀌는지까지 본다.
#
# 확인 항목
#   1. 조회자(viewer)는 명령할 수 없다 (403)
#   2. 운영자 정지 명령 → SUCCEEDED(APPLIED) → 설비 텔레메트리가 STOPPED로 바뀜 (MQTT 설비, Modbus 설비 각각)
#   3. 같은 commandId를 다시 보내도 명령은 하나 (멱등): 감사 로그의 SENT가 늘지 않는다
#   4. 같은 commandId로 다른 내용을 보내면 거부 (409)
#   5. 이미 정지된 설비에 정지 → ALREADY_IN_STATE
#   6. 재개 → RUNNING 복귀 (시험 뒤 설비를 원래대로 돌려놓는 것도 겸한다)
#   7. 회선이 끊긴 동안 명령 → TIMED_OUT, 회선 복구 뒤에도 설비에 적용되지 않음 (toxiproxy 사용, 약 1분)
#
# 사용: scripts/command-check.sh   (compose가 떠 있어야 한다. bash·curl만 쓴다)
set -u

API=${API:-http://localhost:8080/api}
PASS=0
FAIL=0
RUN=$(date +%s)

ok()   { echo "  [통과] $*"; PASS=$((PASS + 1)); }
bad()  { echo "  [실패] $*"; FAIL=$((FAIL + 1)); }

login() {
  curl -fsS -X POST "$API/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" | grep -o '"token":"[^"]*"' | cut -d'"' -f4
}

# 명령 요청. 응답 본문 뒤에 HTTP 코드를 한 줄 붙여 돌려준다
issue() { # token commandId deviceId action
  curl -sS -o - -w '\n%{http_code}' -X POST "$API/commands" -H "Authorization: Bearer $1" \
    -H 'Content-Type: application/json' -d "{\"commandId\":\"$2\",\"deviceId\":\"$3\",\"action\":\"$4\"}"
}

command_field() { # token commandId field → 최근 명령 목록에서 그 명령의 필드 값
  curl -fsS "$API/commands" -H "Authorization: Bearer $1" \
    | grep -o "{\"commandId\":\"$2\"[^}]*}" | grep -o "\"$3\":\"\?[^,\"}]*" | head -1 | sed 's/.*:"\{0,1\}//'
}

device_state() { # token deviceId → 텔레메트리로 본 현재 상태
  curl -fsS "$API/devices" -H "Authorization: Bearer $1" \
    | grep -o "{\"deviceId\":\"$2\"[^}]*}" | grep -o '"state":"[^"]*"' | cut -d'"' -f4
}

audit_events() { # token commandId → 감사 로그 이벤트를 공백으로 이어 붙인 것
  curl -fsS "$API/commands/$2/audit" -H "Authorization: Bearer $1" \
    | grep -o '"event":"[^"]*"' | cut -d'"' -f4 | tr '\n' ' '
}

wait_command() { # token commandId → 끝난 상태(SUCCEEDED/FAILED/TIMED_OUT)가 될 때까지 최대 20초
  local s
  for _ in $(seq 1 40); do
    s=$(command_field "$1" "$2" status)
    case "$s" in SUCCEEDED|FAILED|TIMED_OUT) echo "$s"; return ;; esac
    sleep 0.5
  done
  echo "${s:-NONE}"
}

wait_state() { # token deviceId expected → 텔레메트리 상태가 expected가 될 때까지 최대 10초. 걸린 ms 출력
  local start now
  start=$(date +%s%3N)
  for _ in $(seq 1 40); do
    if [ "$(device_state "$1" "$2")" = "$3" ]; then now=$(date +%s%3N); echo $((now - start)); return 0; fi
    sleep 0.25
  done
  return 1
}

# 정지 → 결과 → 텔레메트리 반영까지 한 설비에 대해
roundtrip() { # token deviceId action expectedState label
  local id="chk-$RUN-$2-$3" t0 t1 status outcome took
  t0=$(date +%s%3N)
  local resp code
  resp=$(issue "$1" "$id" "$2" "$3"); code=$(echo "$resp" | tail -1)
  [ "$code" = 202 ] || { bad "$5: 요청 응답 $code (202 기대)"; return; }
  status=$(wait_command "$1" "$id")
  t1=$(date +%s%3N)
  outcome=$(command_field "$1" "$id" outcome)
  if [ "$status" = SUCCEEDED ]; then ok "$5: $status ($outcome), 요청 → 결과 $((t1 - t0))ms"; else bad "$5: $status ($outcome)"; return; fi
  if took=$(wait_state "$1" "$2" "$4"); then
    ok "$5: 설비 텔레메트리 상태 $4 확인 (결과 뒤 ${took}ms)"
  else
    bad "$5: 텔레메트리 상태가 $4로 바뀌지 않음 (현재 $(device_state "$1" "$2"))"
  fi
  echo "        감사 로그: $(audit_events "$1" "$id")"
}

echo "== 로그인"
OP=$(login operator "${OPERATOR_PASSWORD:-operator123}")
VW=$(login viewer "${VIEWER_PASSWORD:-viewer123}")
[ -n "$OP" ] && [ -n "$VW" ] || { echo "로그인 실패. compose가 떠 있는지 확인"; exit 1; }
ok "operator, viewer 로그인"

echo "== 1. 조회자는 명령 불가"
code=$(issue "$VW" "chk-$RUN-viewer" A-0001 STOP | tail -1)
[ "$code" = 403 ] && ok "viewer 정지 요청 → 403" || bad "viewer 정지 요청 → $code (403 기대)"
[ -z "$(command_field "$OP" "chk-$RUN-viewer" status)" ] && ok "거부된 요청은 명령으로 남지 않음" || bad "거부된 요청이 명령 목록에 있음"

for dev in A-0001 B-0001; do
  proto=$([ "${dev%%-*}" = A ] && echo MQTT || echo Modbus)
  echo "== 2. $dev ($proto) 정지 → 결과 → 텔레메트리"
  [ "$(device_state "$OP" "$dev")" = STOPPED ] && issue "$OP" "chk-$RUN-$dev-pre" "$dev" RESUME >/dev/null && wait_state "$OP" "$dev" RUNNING >/dev/null
  roundtrip "$OP" "$dev" STOP STOPPED "$dev 정지"

  echo "== 3. 같은 commandId 재요청 (멱등)"
  id="chk-$RUN-$dev-STOP"
  before=$(audit_events "$OP" "$id" | grep -o SENT | wc -l)
  code=$(issue "$OP" "$id" "$dev" STOP | tail -1)
  sleep 2
  after=$(audit_events "$OP" "$id" | grep -o SENT | wc -l)
  [ "$before" = "$after" ] && ok "재요청 응답 $code, 설비로 다시 보내지 않음 (SENT $before회 그대로)" || bad "재요청 뒤 SENT $before → $after"

  echo "== 4. 같은 commandId, 다른 내용"
  code=$(issue "$OP" "$id" "$dev" RESUME | tail -1)
  [ "$code" = 409 ] && ok "다른 동작으로 재사용 → 409" || bad "다른 동작으로 재사용 → $code (409 기대)"

  echo "== 5. 이미 정지된 설비에 정지"
  id2="chk-$RUN-$dev-again"
  issue "$OP" "$id2" "$dev" STOP >/dev/null
  s=$(wait_command "$OP" "$id2"); o=$(command_field "$OP" "$id2" outcome)
  [ "$o" = ALREADY_IN_STATE ] && ok "$s ($o)" || bad "$s ($o), ALREADY_IN_STATE 기대"

  echo "== 6. 재개"
  roundtrip "$OP" "$dev" RESUME RUNNING "$dev 재개"
done

echo "== 7. 회선이 끊긴 동안 명령 → 시간 초과, 회선 복구 뒤에도 설비에 적용되지 않음"
# 서버는 5초 × 3회 보내 보고 TIMED_OUT으로 끝낸다. 끝난 명령이 회선 복구 뒤 뒤늦게 실행되면 안 된다
# (운영자는 '실패'로 보고 다른 조치를 했을 수 있다)
TOXI=${TOXI:-http://localhost:8474}
curl -fsS -X POST "$TOXI/proxies/gw-a-uplink" -H 'Content-Type: application/json' -d '{"enabled":false}' >/dev/null
id="chk-$RUN-offline"
code=$(issue "$OP" "$id" A-0002 STOP | tail -1)
s=$(wait_command "$OP" "$id")
curl -fsS -X POST "$TOXI/proxies/gw-a-uplink" -H 'Content-Type: application/json' -d '{"enabled":true}' >/dev/null
[ "$s" = TIMED_OUT ] && ok "요청 $code → $s, 감사 로그: $(audit_events "$OP" "$id")" || bad "회선 단절 중 명령 → $s (TIMED_OUT 기대)"
echo "        회선 복구 후 게이트웨이 재연결과 텔레메트리 갱신을 40초 기다린다"
sleep 40
st=$(device_state "$OP" A-0002)
[ "$st" = RUNNING ] && ok "회선 복구 뒤에도 A-0002는 RUNNING (끝난 명령이 뒤늦게 실행되지 않음)" || bad "A-0002 상태 $st"

echo
echo "결과: 통과 $PASS, 실패 $FAIL"
[ "$FAIL" = 0 ]
