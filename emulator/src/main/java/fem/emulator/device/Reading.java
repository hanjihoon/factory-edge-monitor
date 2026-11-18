package fem.emulator.device;

import fem.common.DeviceState;

/** 설비 한 대의 측정 한 건. 프로토콜별 어댑터가 이것을 MQTT JSON 또는 Modbus 레지스터로 바꾼다. */
public record Reading(
        String deviceId,
        long seq,
        long ts,
        DeviceState state,
        double temperature,
        double vibration,
        int rpm,
        int alarmCode
) {
}
