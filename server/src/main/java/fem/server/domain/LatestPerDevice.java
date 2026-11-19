package fem.server.domain;

import fem.common.TelemetryRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 묶음 안에서 설비마다 seq가 가장 큰 레코드 하나만 남긴다.
 *
 * <p>왜 필요한가: 현재 상태 테이블을 {@code INSERT ... ON CONFLICT DO UPDATE} 한 문장으로 갱신하는데,
 * PostgreSQL은 한 문장이 같은 행을 두 번 갱신하면 오류를 낸다. 단절 복구 후 묶음에는 한 설비의 레코드가
 * 수백 건 들어 있으므로, DB에 보내기 전에 설비당 하나로 줄여야 한다. 어차피 최신 것만 의미가 있다.
 *
 * <p><b>결과는 설비 ID 순으로 정렬한다 (교착 방지).</b> 서버는 묶음 여러 개를 동시에 저장한다. 복구 중 백필 묶음은
 * 대개 같은 설비 수백 대를 함께 담고 있어, 두 트랜잭션이 같은 현재 상태 행들을 잠근다. 묶음 1은 A→B, 묶음 2는 B→A
 * 순서로 잠그면 서로 상대가 쥔 행을 기다리며 멈춘다 (deadlock). 모두 ID 순으로 잠그면 한쪽이 잠시 기다릴 뿐 교착은 없다.
 */
public final class LatestPerDevice {

    private LatestPerDevice() {
    }

    public static List<TelemetryRecord> of(List<TelemetryRecord> records) {
        Map<String, TelemetryRecord> latest = new TreeMap<>(); // 설비 ID 순 = 모든 트랜잭션이 같은 순서로 행을 잠근다
        for (TelemetryRecord r : records) {
            latest.merge(r.deviceId(), r, (a, b) -> b.seq() > a.seq() ? b : a);
        }
        return new ArrayList<>(latest.values());
    }
}
