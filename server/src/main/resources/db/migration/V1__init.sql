-- 텔레메트리 저장 구조 (설계 문서 6장 "저장 구조와 보관 정책")
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- 시계열 원본. 측정 시각(device_ts)으로 시간 분할(hypertable).
-- 주의: hypertable의 유니크 인덱스에는 시간 컬럼이 반드시 들어가야 해서 (device_id, seq) 단독 유니크를 걸 수 없다.
--       중복 판정은 아래 telemetry_dedup이 맡고, 여기 기본 키는 조회용이다.
CREATE TABLE telemetry (
    device_id   TEXT             NOT NULL,
    seq         BIGINT           NOT NULL,
    factory_id  TEXT             NOT NULL,
    protocol    TEXT             NOT NULL,
    device_ts   TIMESTAMPTZ      NOT NULL,
    gateway_ts  TIMESTAMPTZ      NOT NULL,
    server_ts   TIMESTAMPTZ      NOT NULL DEFAULT now(), -- 저장 시각. 종단 지연·적체 측정에 쓴다
    state       TEXT             NOT NULL,
    temperature DOUBLE PRECISION NOT NULL,
    vibration   DOUBLE PRECISION NOT NULL,
    rpm         INTEGER          NOT NULL,
    alarm_code  INTEGER          NOT NULL,
    PRIMARY KEY (device_id, device_ts, seq)
);
SELECT create_hypertable('telemetry', 'device_ts', chunk_time_interval => INTERVAL '1 day');
SELECT add_retention_policy('telemetry', INTERVAL '30 days');

-- 중복 판정 전용 일반 테이블. (device_id, seq)가 처음 들어올 때만 행이 생긴다.
-- 재전송 중복은 최대 버퍼 보관 시간(6시간) 안에서만 생기므로 2일만 보관한다 (서버 스케줄러가 정리).
CREATE TABLE telemetry_dedup (
    device_id   TEXT        NOT NULL,
    seq         BIGINT      NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (device_id, seq)
);
CREATE INDEX telemetry_dedup_received_at ON telemetry_dedup (received_at);

-- 설비 현재 상태. seq가 더 클 때만 갱신한다 (순서 뒤바뀜에도 최신 값 유지).
CREATE TABLE device_current (
    device_id    TEXT             PRIMARY KEY,
    factory_id   TEXT             NOT NULL,
    seq          BIGINT           NOT NULL,
    device_ts_ms BIGINT           NOT NULL,
    state        TEXT             NOT NULL,
    temperature  DOUBLE PRECISION NOT NULL,
    vibration    DOUBLE PRECISION NOT NULL,
    rpm          INTEGER          NOT NULL,
    alarm_code   INTEGER          NOT NULL,
    updated_at   TIMESTAMPTZ      NOT NULL DEFAULT now()
);
