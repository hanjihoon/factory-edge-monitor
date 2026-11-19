package fem.common.wire;

import fem.common.TelemetryRecord;

import java.util.List;

/**
 * 게이트웨이 ↔ 서버 WebSocket 메시지 계약.
 *
 * <p>연결은 항상 게이트웨이가 먼저 연다(공장망은 아웃바운드만 허용).
 * 서버의 원격 명령도 이 연결을 거꾸로 타고 내려간다.
 *
 * <p>프레임 형식: {@code {"type": "<MessageType>", "body": {...}}}
 * common 모듈은 의존성이 없으므로 JSON 처리는 각 모듈이 type을 보고 body를 해당 record로 변환한다.
 */
public final class WireMessages {

    private WireMessages() {
    }

    /**
     * 게이트웨이 → 서버. 디스크 버퍼(outbox)에서 꺼낸 묶음.
     *
     * <p>outboxId는 게이트웨이 로컬에서 단조 증가하는 번호다. 서버는 이 묶음을 DB에 커밋한 뒤
     * {@link Ack}로 {@code toOutboxId}를 돌려준다. 게이트웨이는 그 번호 이하를 디스크에서 지운다.
     * 커밋 전에 연결이 끊기면 ack가 안 오므로 게이트웨이는 같은 묶음을 다시 보낸다
     * → 서버 입장에서는 중복이 생기지만 (deviceId, seq) 유니크 키로 걸러진다.
     * 즉 "최소 한 번 전달 + 서버 측 중복 제거 = 결과적으로 정확히 한 번 저장"이다.
     */
    public record Batch(String gatewayId, long fromOutboxId, long toOutboxId, List<TelemetryRecord> records) {
    }

    /** 서버 → 게이트웨이. toOutboxId까지 커밋 완료 (누적 ack). */
    public record Ack(long toOutboxId) {
    }

    /**
     * 게이트웨이 → 서버. 5초마다 보내는 상태 보고.
     * 관제 UI의 "게이트웨이 연결 상태와 버퍼 적체량" 표시에 쓴다.
     */
    public record GatewayStatus(
            String gatewayId,
            long bufferedCount,       // 아직 ack 받지 못한 레코드 수
            long oldestBufferedAgeMs, // 가장 오래된 미전송 레코드의 나이
            int connectedDevices,     // 최근 5초 안에 데이터가 들어온 설비 수
            long droppedCount,        // 버퍼 상한 초과로 버린 누적 건수 (게이트웨이 기동 이후). 0이 아니면 유실 알람
            long sentAt
    ) {
    }

    /** 서버 → 게이트웨이. 원격 명령. commandId가 멱등 키다. */
    public record Command(String commandId, String deviceId, CommandAction action, long issuedAt, int attempt) {
    }

    /**
     * 게이트웨이 → 서버. 명령 실행 결과. 같은 commandId에 대해 여러 번 와도 서버는 처음 것만 반영한다.
     * 재시도된 명령에는 게이트웨이가 처음 결과를 그대로 다시 보낸다 ("중복"이라는 별도 결과를 두지 않는다.
     * 그래야 서버가 재시도 응답만 받아도 실제로 적용됐는지 알 수 있다).
     */
    public record CommandResult(String commandId, String deviceId, CommandOutcome outcome, String detail, long executedAt) {
    }

    public enum CommandAction {
        STOP,
        RESUME
    }

    public enum CommandOutcome {
        APPLIED,            // 설비 상태가 바뀜
        ALREADY_IN_STATE,   // 이미 그 상태라 아무것도 안 함 (멱등)
        DEVICE_UNREACHABLE, // 게이트웨이가 설비에 명령을 못 씀
        UNKNOWN_DEVICE
    }

    /** 프레임의 type 값과 body 클래스 매핑. */
    public enum MessageType {
        BATCH(Batch.class),
        ACK(Ack.class),
        GATEWAY_STATUS(GatewayStatus.class),
        COMMAND(Command.class),
        COMMAND_RESULT(CommandResult.class);

        private final Class<?> bodyType;

        MessageType(Class<?> bodyType) {
            this.bodyType = bodyType;
        }

        public Class<?> bodyType() {
            return bodyType;
        }

        public static MessageType of(Object body) {
            for (MessageType t : values()) {
                if (t.bodyType.isInstance(body)) return t;
            }
            throw new IllegalArgumentException("not a wire message: " + body.getClass());
        }
    }
}
