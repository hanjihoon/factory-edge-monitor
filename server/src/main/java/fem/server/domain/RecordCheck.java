package fem.server.domain;

import fem.common.TelemetryRecord;

import java.util.Optional;

/**
 * 저장하기 전에 레코드 하나가 "몇 번을 다시 보내도 저장될 수 없는 데이터"인지 가린다.
 *
 * <h2>왜 필요한가 (독이 든 메시지)</h2>
 * 저장이 실패하면 서버는 ack 없이 연결을 끊고 게이트웨이는 같은 묶음을 다시 보낸다. DB가 잠깐 죽은 경우라면
 * 이게 맞다. 그런데 데이터 자체가 잘못됐으면 몇 번을 보내도 실패한다 → 끊기·재연결·재전송이 끝없이 반복되고
 * 그 게이트웨이의 정상 데이터까지 전부 막힌다. 그래서 그런 레코드는 미리 골라내 격리 테이블에 보관하고
 * (버리지 않으니 유실이 아니다) 나머지는 정상 처리한다.
 *
 * <p>여기서 거르는 것은 "DB에 넣으면 예외가 나거나, 넣으면 안 되는 것"뿐이다. 값의 범위(온도가 이상하다 등)는
 * 측정값 자체이므로 그대로 저장한다.
 */
public final class RecordCheck {

    /** PostgreSQL timestamptz로 바꿀 수 있는 넉넉한 상한 (9999-12-31). 넘으면 to_timestamp가 오류를 낸다 */
    static final long MAX_EPOCH_MS = 253_402_300_799_999L;

    private RecordCheck() {
    }

    /**
     * @param factoryId 이 묶음을 보낸 게이트웨이가 인증으로 확인된 공장
     * @return 저장하면 안 되는 이유. 문제없으면 empty
     */
    public static Optional<String> problem(TelemetryRecord r, String factoryId) {
        if (r == null) return Optional.of("null record");
        // 상태·프로토콜이 null이면 대개 서버가 모르는 값이다 (새 버전 게이트웨이). 코덱이 모르는 enum 값을 null로 읽는다
        if (r.deviceId() == null || r.factoryId() == null || r.protocol() == null || r.state() == null) {
            return Optional.of("missing or unknown field (deviceId/factoryId/protocol/state)");
        }
        // 인증된 게이트웨이가 남의 공장 설비 데이터를 보내면 안 된다
        if (!factoryId.equals(r.factoryId()) || !r.deviceId().startsWith(factoryId + "-")) {
            return Optional.of("record outside factory " + factoryId);
        }
        if (!validTime(r.deviceTs()) || !validTime(r.gatewayTs())) {
            return Optional.of("timestamp out of range");
        }
        return Optional.empty();
    }

    private static boolean validTime(long epochMs) {
        return epochMs > 0 && epochMs <= MAX_EPOCH_MS;
    }
}
