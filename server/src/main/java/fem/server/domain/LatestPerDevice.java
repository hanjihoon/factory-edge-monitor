package fem.server.domain;

import fem.common.TelemetryRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 묶음 안에서 설비마다 seq가 가장 큰 레코드 하나만 남긴다.
 *
 * <p>왜 필요한가: 현재 상태 테이블을 {@code INSERT ... ON CONFLICT DO UPDATE} 한 문장으로 갱신하는데,
 * PostgreSQL은 한 문장이 같은 행을 두 번 갱신하면 오류를 낸다. 단절 복구 후 묶음에는 한 설비의 레코드가
 * 수백 건 들어 있으므로, DB에 보내기 전에 설비당 하나로 줄여야 한다. 어차피 최신 것만 의미가 있다.
 */
public final class LatestPerDevice {

    private LatestPerDevice() {
    }

    public static List<TelemetryRecord> of(List<TelemetryRecord> records) {
        Map<String, TelemetryRecord> latest = new LinkedHashMap<>();
        for (TelemetryRecord r : records) {
            latest.merge(r.deviceId(), r, (a, b) -> b.seq() > a.seq() ? b : a);
        }
        return new ArrayList<>(latest.values());
    }
}
