package fem.gateway.normalize;

import fem.common.Protocol;
import fem.common.TelemetryRecord;
import fem.common.modbus.ModbusRegisterMap;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 공장 B Modbus 레지스터 → 공통 모델 (설계 문서 6장 Modbus 매핑표).
 *
 * <p>폴링 주기(0.5초)가 설비 갱신 주기(1초)보다 짧으므로 같은 카운터를 두 번 읽는 일이 잦다.
 * 이미 본 seq 이하이면 버려서 업링크로 쓸데없는 중복을 보내지 않는다.
 * (게이트웨이가 재시작해 기억을 잃어도 서버의 (deviceId, seq) 중복 제거가 최종 방어선이다.)
 *
 * <p>왜 폴링을 1초보다 짧게 하나: 1초로 맞추면 설비 시계와 위상이 어긋날 때 카운터 하나를 건너뛰는 일이 생긴다.
 * 그러면 그 샘플은 수집 단계에서 영영 사라진다. 두 배로 자주 읽고 중복을 버리는 편이 안전하다.
 */
public final class ModbusNormalizer {

    private final String factoryId;
    private final int basePort;
    /** 설비별로 마지막으로 내보낸 seq */
    private final Map<String, Long> lastSeq = new ConcurrentHashMap<>();

    public ModbusNormalizer(String factoryId, int basePort) {
        this.factoryId = factoryId;
        this.basePort = basePort;
    }

    /**
     * @param registers 0~6번 레지스터 값
     * @param pollTs    폴링 시각. 레지스터에 측정 시각이 없으므로 deviceTs와 gatewayTs 모두 이 값 (가정 3)
     * @return 새 샘플이면 레코드, 이미 본 카운터면 비어 있음
     */
    public Optional<TelemetryRecord> normalize(int port, int unit, int[] registers, long pollTs) {
        String deviceId = ModbusRegisterMap.deviceId(factoryId, basePort, port, unit);
        ModbusRegisterMap.Sample s = ModbusRegisterMap.decode(registers);

        // compute는 같은 키에 대해 원자적으로 실행된다. "비교 후 갱신"이 다른 스레드와 섞이지 않는다.
        boolean[] isNew = {false};
        lastSeq.compute(deviceId, (id, prev) -> {
            if (prev == null || s.seq() > prev) {
                isNew[0] = true;
                return s.seq();
            }
            return prev;
        });
        if (!isNew[0]) {
            return Optional.empty();
        }
        return Optional.of(new TelemetryRecord(deviceId, factoryId, Protocol.MODBUS, s.seq(), pollTs, pollTs,
                s.state(), s.temperature(), s.vibration(), s.rpm(), s.alarmCode()));
    }
}
