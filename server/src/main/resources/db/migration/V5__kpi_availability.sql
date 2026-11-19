-- 가동률 KPI (선택 구현). 설계 문서 6장 "KPI 집계".
--
-- 가동률 = 가동(RUNNING) 상태로 보고된 샘플 수 ÷ 전체 샘플 수. 설비는 1초마다 보고하므로 "가동 초 ÷ 보고 초"와 같다.
-- OEE(= 가동률 × 성능 × 품질) 중 성능·품질은 생산 수량·불량 수가 있어야 하는데 이 데이터에는 없다. 그래서 가동률만 낸다.
--
-- 왜 연속 집계(continuous aggregate)인가:
--   화면이 열릴 때마다 원본(초당 1,000행, 하루 8,640만 행)을 훑으면 느리다. 1분 단위로 미리 세어 두면
--   24시간 KPI도 1,000대 × 1,440분 = 144만 행만 읽는다. 압축·보관과 같은 TimescaleDB 기능이라 구성 요소가 늘지 않는다.
CREATE MATERIALIZED VIEW device_state_1m
WITH (timescaledb.continuous, timescaledb.materialized_only = false) AS
-- materialized_only = false: 아직 집계되지 않은 최근 몇 분은 원본에서 바로 세어 합친다 (실시간 집계). 화면이 1분 늦지 않다
SELECT time_bucket(INTERVAL '1 minute', device_ts) AS bucket,
       device_id,
       factory_id,
       count(*)                                    AS samples,
       count(*) FILTER (WHERE state = 'RUNNING')   AS running,
       count(*) FILTER (WHERE state = 'STOPPED')   AS stopped,
       count(*) FILTER (WHERE state = 'FAULT')     AS fault
FROM telemetry
GROUP BY bucket, device_id, factory_id
WITH NO DATA;

-- 1분마다 "7시간 전 ~ 1분 전" 구간을 다시 센다.
-- 7시간인 이유: 회선이 끊기면 데이터가 최대 6시간(게이트웨이 버퍼 상한) 늦게 도착한다. 그 늦은 데이터도 집계에 들어가야
-- 단절 구간의 가동률이 비지 않는다. 실제로는 바뀐 구간만 다시 계산하므로(무효화 기록) 매번 7시간을 다 세지는 않는다.
SELECT add_continuous_aggregate_policy('device_state_1m',
    start_offset      => INTERVAL '7 hours',
    end_offset        => INTERVAL '1 minute',
    schedule_interval => INTERVAL '1 minute');

-- 집계도 원본처럼 30일만 둔다
SELECT add_retention_policy('device_state_1m', INTERVAL '30 days');
