package fem.server.application;

import fem.common.TelemetryRecord;
import fem.server.application.port.out.TelemetryRepository;
import fem.server.domain.DeviceSnapshot;
import fem.server.domain.RejectedRecord;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * JDBC 구현과 같은 규칙((deviceId, seq) 중복 제거, seq가 클 때만 현재 상태 갱신)을 메모리로 흉내 낸다.
 * DB 없이 애플리케이션 계층과 WebSocket 흐름을 시험할 때 쓴다.
 */
public class InMemoryTelemetryRepository implements TelemetryRepository {

    public final List<TelemetryRecord> stored = new ArrayList<>();
    private final Set<String> keys = new HashSet<>();
    private final Map<String, DeviceSnapshot> current = new TreeMap<>();
    public RuntimeException failNext;

    @Override
    public synchronized int insertNew(List<TelemetryRecord> records) {
        if (failNext != null) {
            RuntimeException e = failNext;
            failNext = null;
            throw e;
        }
        int n = 0;
        for (TelemetryRecord r : records) {
            if (keys.add(r.deviceId() + "#" + r.seq())) {
                stored.add(r);
                n++;
            }
        }
        return n;
    }

    @Override
    public synchronized List<DeviceSnapshot> upsertCurrent(List<TelemetryRecord> latest) {
        List<DeviceSnapshot> changed = new ArrayList<>();
        for (TelemetryRecord r : latest) {
            DeviceSnapshot old = current.get(r.deviceId());
            if (old == null || old.seq() < r.seq()) {
                DeviceSnapshot s = new DeviceSnapshot(r.deviceId(), r.factoryId(), r.seq(), r.deviceTs(), r.state().name(),
                        r.temperature(), r.vibration(), r.rpm(), r.alarmCode());
                current.put(r.deviceId(), s);
                changed.add(s);
            }
        }
        return changed;
    }

    @Override
    public synchronized List<DeviceSnapshot> findAllCurrent() {
        return List.copyOf(current.values());
    }

    public final List<RejectedRecord> quarantined = new ArrayList<>();

    @Override
    public synchronized void quarantine(String gatewayId, List<RejectedRecord> rejected) {
        quarantined.addAll(rejected);
    }
}
