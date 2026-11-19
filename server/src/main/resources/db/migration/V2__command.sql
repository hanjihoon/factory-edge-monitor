-- 원격 명령과 감사 로그 (5단계)

-- 명령 현재 상태. commandId는 요청자(UI)가 만든 값이며 멱등 키다.
CREATE TABLE command (
    command_id   TEXT    PRIMARY KEY,
    device_id    TEXT    NOT NULL,
    gateway_id   TEXT    NOT NULL,
    action       TEXT    NOT NULL,
    requested_by TEXT    NOT NULL,
    created_at   BIGINT  NOT NULL,   -- epoch ms
    status       TEXT    NOT NULL,   -- PENDING, SENT, SUCCEEDED, FAILED, TIMED_OUT
    attempts     INTEGER NOT NULL,
    last_sent_at BIGINT  NOT NULL,
    completed_at BIGINT  NOT NULL,
    outcome      TEXT,
    detail       TEXT
);
-- 재시도 검사는 끝나지 않은 명령만 본다. 부분 인덱스로 작게 유지
CREATE INDEX command_open ON command (status) WHERE status IN ('PENDING', 'SENT');
CREATE INDEX command_recent ON command (created_at DESC);

-- 감사 로그: 추가만 한다. 애플리케이션 코드에 수정·삭제 경로가 없다.
-- (운영에서는 애플리케이션 DB 계정에서 UPDATE/DELETE 권한을 빼서 강제한다 → 설계 문서 12장)
CREATE TABLE command_audit (
    id         BIGSERIAL PRIMARY KEY,
    command_id TEXT   NOT NULL,
    at         BIGINT NOT NULL,
    event      TEXT   NOT NULL,
    actor      TEXT   NOT NULL,
    detail     TEXT
);
CREATE INDEX command_audit_by_command ON command_audit (command_id, id);
