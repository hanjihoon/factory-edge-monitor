-- 저장할 수 없는 레코드 격리 (dead letter). 설계 문서 8장 "잘못된 데이터".
-- 잘못된 레코드 때문에 예외로 ack가 안 나가면 게이트웨이가 같은 묶음을 끝없이 재전송하고 그 뒤 데이터까지 막힌다.
-- 그런 레코드는 원문을 여기 남기고 나머지는 정상 저장·ack한다. 버리지 않으므로 유실이 아니다.
CREATE TABLE ingest_rejected (
    id          BIGSERIAL   PRIMARY KEY,
    gateway_id  TEXT        NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason      TEXT        NOT NULL,
    payload     TEXT        NOT NULL
);
CREATE INDEX ingest_rejected_recent ON ingest_rejected (received_at DESC);
