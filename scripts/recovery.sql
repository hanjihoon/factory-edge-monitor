-- 회선 복구 직후 "새로 측정된 데이터"가 얼마나 늦게 저장되는가 (설계 문서 10장).
-- 사용: psql -v restore="'2026-11-19 12:00:00+00'" -f recovery.sql
--
-- 적체 소진 시간(복구 시간)과는 다른 지표다. 적체가 남아 있어도 새 데이터가 먼저 들어오면
-- 관제 화면은 곧바로 현재를 보여 준다. FIFO 전송이면 새 데이터가 적체 뒤에 줄을 서므로
-- 복구 직후 측정된 데이터일수록 지연이 크다.
--
-- 측정 시각(device_ts)을 복구 시각 기준 10초 구간으로 나누고, 구간마다 측정 → DB 지연 p95를 본다.

\echo '== 5. 복구 직후 측정된 데이터의 측정 → DB 지연 (복구 시각 기준 10초 구간, 공장별 p95 ms)'
WITH b AS (
    SELECT factory_id,
           floor(extract(epoch FROM device_ts - :restore::timestamptz) / 10)::int * 10 AS from_sec,
           extract(epoch FROM server_ts - device_ts) * 1000 AS lat_ms
    FROM telemetry
    WHERE device_ts >= :restore::timestamptz AND device_ts < :restore::timestamptz + interval '180 seconds'
)
SELECT from_sec || '~' || (from_sec + 10) || 's' AS measured_after_restore,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY lat_ms) FILTER (WHERE factory_id = 'A')::numeric) AS a_p95_ms,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY lat_ms) FILTER (WHERE factory_id = 'B')::numeric) AS b_p95_ms
FROM b GROUP BY from_sec ORDER BY from_sec;

\echo '== 6. 실시간 복귀: 복구 후 처음으로 p95 2초 미만이 된 10초 구간 (공장별)'
\echo '   "이후 모든 구간이 2초 미만"으로 두면 적체와 무관한 일시적 지연 하나에 수십 초가 더해져 5번 표로 따로 본다'
WITH b AS (
    SELECT factory_id,
           floor(extract(epoch FROM device_ts - :restore::timestamptz) / 10)::int * 10 AS from_sec,
           percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM server_ts - device_ts) * 1000) AS p95
    FROM telemetry
    WHERE device_ts >= :restore::timestamptz AND device_ts < :restore::timestamptz + interval '180 seconds'
    GROUP BY factory_id, from_sec
)
SELECT factory_id, min(from_sec) AS live_from_sec_after_restore
FROM b WHERE p95 < 2000 GROUP BY factory_id ORDER BY factory_id;
