-- 시계열 압축 (설계 문서 6장 "저장 구조와 보관 정책").
-- 압축이 없으면 30일 보관에 약 520GB가 든다 (실측 1행 약 200B × 초당 1,000건 × 30일).
--
-- segmentby = device_id: 같은 설비의 값끼리 모아 열 단위로 압축한다. 설비 하나의 값은 천천히 변하므로 잘 줄어들고,
--                        "설비 X의 지난 1시간" 같은 조회는 그 설비 묶음만 풀면 된다.
-- orderby   = device_ts: 묶음 안을 시간순으로 둬 시각 차이(델타) 압축이 잘 되게 한다.
ALTER TABLE telemetry SET (
    timescaledb.compress,
    timescaledb.compress_segmentby = 'device_id',
    timescaledb.compress_orderby = 'device_ts DESC, seq DESC'
);

-- 2일 지난 청크만 압축한다. 늦게 도착하는 데이터는 게이트웨이 버퍼 상한(6시간) 안에서만 생기고 청크는 1일 단위라,
-- 2일 지난 청크에는 더 이상 쓰기가 오지 않는다 (압축된 청크에 쓰면 느려지므로 피한다).
SELECT add_compression_policy('telemetry', INTERVAL '2 days', if_not_exists => true);
