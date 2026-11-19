-- 측정 보고 (설계 문서 10장). 사용: psql -v from="'2026-11-19 10:00:00+09'" -v to="'2026-11-19 10:20:00+09'" -f report.sql
-- from/to: 측정 구간 (device_ts 기준)

\echo '== 1. 처리량: 구간 안 초당 저장 건수 (서버 저장 시각 기준)'
SELECT count(*) AS rows,
       round(count(*) / greatest(extract(epoch FROM max(server_ts) - min(server_ts)), 1)::numeric, 1) AS rows_per_sec
FROM telemetry WHERE device_ts BETWEEN :from AND :to;

\echo '== 2. 측정 → DB 저장 지연 (공장별, ms). 단절 구간이 포함되면 적체 시간이 섞인다'
SELECT factory_id,
       round(percentile_cont(0.50) WITHIN GROUP (ORDER BY extract(epoch FROM server_ts - device_ts) * 1000)::numeric) AS p50_ms,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM server_ts - device_ts) * 1000)::numeric) AS p95_ms,
       round(max(extract(epoch FROM server_ts - device_ts) * 1000)::numeric) AS max_ms
FROM telemetry WHERE device_ts BETWEEN :from AND :to
GROUP BY factory_id ORDER BY factory_id;

\echo '== 3. 중복: (device_id, seq)가 두 번 이상 저장된 건수 → 0이어야 한다'
SELECT count(*) - count(DISTINCT (device_id, seq)) AS duplicate_rows
FROM telemetry WHERE device_ts BETWEEN :from AND :to;

\echo '== 4. 유실: 설비마다 구간 안 seq의 빈 번호 개수 (첫 seq ~ 마지막 seq 사이)'
\echo '   MQTT(A)는 설비가 매초 발행하므로 0이어야 한다. Modbus(B)는 폴링이 샘플을 건너뛰면 수집 단계 누락이 생길 수 있다'
SELECT factory_id,
       count(*) AS devices,
       sum(max_seq - min_seq + 1 - n) AS missing_seqs,
       sum(n) AS stored
FROM (SELECT factory_id, device_id, min(seq) AS min_seq, max(seq) AS max_seq, count(DISTINCT seq) AS n
      FROM telemetry WHERE device_ts BETWEEN :from AND :to
      GROUP BY factory_id, device_id) per_device
GROUP BY factory_id ORDER BY factory_id;
