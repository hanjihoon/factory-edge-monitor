package fem.server.domain;

import fem.common.wire.WireMessages.CommandAction;
import fem.common.wire.WireMessages.CommandOutcome;

/**
 * 원격 명령 하나의 상태 기계. 순수 로직이라 시간(now)을 인자로 받는다.
 *
 * <pre>
 *  PENDING ──전송──▶ SENT ──결과(APPLIED/ALREADY_IN_STATE)──▶ SUCCEEDED
 *     ▲               │  ──결과(UNKNOWN_DEVICE)──────────────▶ FAILED
 *     │               │  ──결과(DEVICE_UNREACHABLE) 또는 응답 시간 초과
 *     └── 재시도 ◀────┘        └─ 시도 횟수 소진 ──▶ TIMED_OUT
 * </pre>
 *
 * 재시도해도 commandId는 바뀌지 않는다. 게이트웨이와 설비가 commandId로 중복을 걸러 한 번만 적용한다.
 */
public final class RemoteCommand {

    public enum Status {
        PENDING, SENT, SUCCEEDED, FAILED, TIMED_OUT;

        public boolean terminal() {
            return this == SUCCEEDED || this == FAILED || this == TIMED_OUT;
        }
    }

    /** 재시도 판단 결과 */
    public enum TimeoutDecision { NOT_DUE, RETRY, GIVE_UP }

    private final String commandId;
    private final String deviceId;
    private final String gatewayId;
    private final CommandAction action;
    private final String requestedBy;
    private final long createdAt;
    private Status status;
    private int attempts;
    private long lastSentAt;
    private long completedAt;
    private CommandOutcome outcome;
    private String detail;

    public RemoteCommand(String commandId, String deviceId, String gatewayId, CommandAction action,
                         String requestedBy, long createdAt) {
        this(commandId, deviceId, gatewayId, action, requestedBy, createdAt, Status.PENDING, 0, 0, 0, null, null);
    }

    /** 저장소에서 다시 만들 때 */
    public RemoteCommand(String commandId, String deviceId, String gatewayId, CommandAction action, String requestedBy,
                         long createdAt, Status status, int attempts, long lastSentAt, long completedAt,
                         CommandOutcome outcome, String detail) {
        this.commandId = commandId;
        this.deviceId = deviceId;
        this.gatewayId = gatewayId;
        this.action = action;
        this.requestedBy = requestedBy;
        this.createdAt = createdAt;
        this.status = status;
        this.attempts = attempts;
        this.lastSentAt = lastSentAt;
        this.completedAt = completedAt;
        this.outcome = outcome;
        this.detail = detail;
    }

    /** 게이트웨이로 한 번 보냈다 (전송 시도 1회) */
    public void markSent(long now) {
        requireOpen();
        attempts++;
        lastSentAt = now;
        status = Status.SENT;
    }

    /** 게이트웨이가 연결돼 있지 않아 보내지 못했다. 시도 횟수에는 넣되 대기 상태로 둔다 */
    public void markSendFailed(long now) {
        requireOpen();
        attempts++;
        lastSentAt = now;
        status = Status.PENDING;
    }

    /**
     * 게이트웨이 결과 반영.
     *
     * @return 상태가 바뀌었으면 true. 이미 끝난 명령에 온 결과(재시도로 생긴 중복 응답)는 false.
     *         단 TIMED_OUT 뒤에 온 확정 결과는 반영한다 (늦은 결과)
     */
    public boolean applyResult(CommandOutcome result, String resultDetail, long now) {
        // 시간 초과는 "결과를 모른다"는 뜻이지 "실패"가 아니다. 회선이 느려 결과가 늦게 왔다면 설비에는 이미
        // 적용된 것이므로, 그 사실로 바로잡는다 (안 그러면 화면은 '시간 초과'인데 설비는 멈춰 있는 상태가 된다).
        // DEVICE_UNREACHABLE은 확정 결과가 아니라 재시도 신호라서 끝난 명령을 바꾸지 않는다.
        if (status == Status.TIMED_OUT && result != CommandOutcome.DEVICE_UNREACHABLE) {
            finish(result == CommandOutcome.UNKNOWN_DEVICE ? Status.FAILED : Status.SUCCEEDED, result, resultDetail, now);
            return true;
        }
        if (status.terminal()) return false;
        switch (result) {
            case APPLIED, ALREADY_IN_STATE -> finish(Status.SUCCEEDED, result, resultDetail, now);
            case UNKNOWN_DEVICE -> finish(Status.FAILED, result, resultDetail, now);
            case DEVICE_UNREACHABLE -> {
                // 설비가 응답하지 않았다. PENDING + 이 결과 조합이면 checkTimeout이 바로 재시도로 판단한다.
                // (별도 플래그를 두지 않는 이유: DB에서 다시 읽어도 저장된 값만으로 같은 판단이 나와야 한다)
                outcome = result;
                detail = resultDetail;
                status = Status.PENDING;
            }
        }
        return true;
    }

    /**
     * 응답 없이 시간이 지났는지 판단한다.
     *
     * @param ackTimeoutMs 시도당 응답 대기 시간
     * @param maxAttempts  최대 시도 횟수
     */
    public TimeoutDecision checkTimeout(long now, long ackTimeoutMs, int maxAttempts) {
        if (status.terminal()) return TimeoutDecision.NOT_DUE;
        boolean unreachableReported = status == Status.PENDING && outcome == CommandOutcome.DEVICE_UNREACHABLE;
        // 게이트웨이 미연결(SEND_FAILED)로 PENDING인 경우는 기다린다. 끊긴 동안 1초마다 헛되이 시도하지 않게
        if (!unreachableReported && now - lastSentAt < ackTimeoutMs) return TimeoutDecision.NOT_DUE;
        return attempts >= maxAttempts ? TimeoutDecision.GIVE_UP : TimeoutDecision.RETRY;
    }

    public void markTimedOut(long now) {
        requireOpen();
        finish(Status.TIMED_OUT, outcome, detail == null ? "no result after " + attempts + " attempts" : detail, now);
    }

    private void finish(Status s, CommandOutcome o, String d, long now) {
        status = s;
        outcome = o;
        detail = d;
        completedAt = now;
    }

    private void requireOpen() {
        if (status.terminal()) throw new IllegalStateException("command " + commandId + " already " + status);
    }

    public String commandId() { return commandId; }
    public String deviceId() { return deviceId; }
    public String gatewayId() { return gatewayId; }
    public CommandAction action() { return action; }
    public String requestedBy() { return requestedBy; }
    public long createdAt() { return createdAt; }
    public Status status() { return status; }
    public int attempts() { return attempts; }
    public long lastSentAt() { return lastSentAt; }
    public long completedAt() { return completedAt; }
    public CommandOutcome outcome() { return outcome; }
    public String detail() { return detail; }
}
