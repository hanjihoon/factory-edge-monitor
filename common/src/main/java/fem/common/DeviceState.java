package fem.common;

/**
 * 설비 운전 상태. Modbus 레지스터에는 숫자 코드로 들어 있으므로 코드 변환을 함께 둔다.
 */
public enum DeviceState {
    RUNNING(1),
    STOPPED(2),  // 원격 정지 명령으로 멈춘 상태
    FAULT(3);    // 설비 자체 고장 (알람 코드 동반)

    private final int code;

    DeviceState(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static DeviceState fromCode(int code) {
        for (DeviceState s : values()) {
            if (s.code == code) return s;
        }
        throw new IllegalArgumentException("unknown device state code: " + code);
    }
}
