package fem.server.application.port.out;

import fem.common.TelemetryRecord;
import fem.server.domain.DeviceSnapshot;

import java.util.List;

/** 텔레메트리 저장소 (출력 포트). 구현: JdbcTelemetryRepository */
public interface TelemetryRepository {

    /**
     * 처음 보는 (deviceId, seq)만 저장한다. 이미 저장된 것과 묶음 안 중복은 건너뛴다.
     *
     * @return 실제로 저장된 건수
     */
    int insertNew(List<TelemetryRecord> records);

    /**
     * 설비 현재 상태를 갱신한다. 저장된 seq보다 클 때만 바뀐다.
     *
     * @param latest 설비당 하나 (같은 설비가 두 번 들어 있으면 안 된다)
     * @return 실제로 바뀐 설비들의 새 상태
     */
    List<DeviceSnapshot> upsertCurrent(List<TelemetryRecord> latest);

    /** 전체 설비 현재 상태 (화면 첫 로딩) */
    List<DeviceSnapshot> findAllCurrent();
}
