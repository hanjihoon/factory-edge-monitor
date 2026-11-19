package fem.server.domain;

/**
 * 설비 현재 상태 (device_current 한 행). 관제 화면 목록과 실시간 갱신에 쓴다.
 *
 * @param deviceTs 측정 시각(epoch ms)
 */
public record DeviceSnapshot(
        String deviceId,
        String factoryId,
        long seq,
        long deviceTs,
        String state,
        double temperature,
        double vibration,
        int rpm,
        int alarmCode
) {
}
