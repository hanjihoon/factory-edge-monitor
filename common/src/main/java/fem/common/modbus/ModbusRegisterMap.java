package fem.common.modbus;

import fem.common.DeviceIds;
import fem.common.DeviceState;

/**
 * 공장 B Modbus 설비의 Holding Register 맵 (설계 문서 6장).
 *
 * <pre>
 *  주소  내용                       변환
 *  0~1   샘플 카운터 uint32 (상·하)  seq = (r0 << 16) | r1
 *  2     상태 코드                  1=RUNNING 2=STOPPED 3=FAULT
 *  3     온도 × 10 (int16, 부호 있음) ÷ 10
 *  4     진동 × 100 (uint16)         ÷ 100
 *  5     회전수 (uint16)             그대로
 *  6     알람 코드 (uint16)          그대로
 *  10    제어 (쓰기)                 1=RESUME 2=STOP, 설비가 처리 후 0으로 되돌림
 * </pre>
 *
 * 에뮬레이터는 {@link #encode}로 쓰고 게이트웨이는 {@link #decode}로 읽는다.
 * 같은 코드를 공유하므로 양쪽 해석이 어긋나지 않는다.
 */
public final class ModbusRegisterMap {

    public static final int COUNTER_HI = 0;
    public static final int COUNTER_LO = 1;
    public static final int STATE = 2;
    public static final int TEMPERATURE = 3;
    public static final int VIBRATION = 4;
    public static final int RPM = 5;
    public static final int ALARM = 6;
    public static final int CONTROL = 10;

    /** 게이트웨이가 한 번에 읽는 개수 (0~6) */
    public static final int TELEMETRY_COUNT = 7;
    /** 설비 하나가 가진 레지스터 수 (0~10) */
    public static final int TOTAL_COUNT = 11;

    public static final int CONTROL_NONE = 0;
    public static final int CONTROL_RESUME = 1;
    public static final int CONTROL_STOP = 2;

    /** 라인 컨트롤러 하나(=포트 하나)에 붙는 설비 수 */
    public static final int UNITS_PER_PORT = 100;

    private ModbusRegisterMap() {
    }

    /** 레지스터 0~6에서 읽은 값 */
    public record Sample(long seq, DeviceState state, double temperature, double vibration, int rpm, int alarmCode) {
    }

    /** 0~6번 레지스터 값(각 0~65535)을 만든다. */
    public static int[] encode(Sample s) {
        if (s.seq() < 0 || s.seq() > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException("seq out of uint32 range: " + s.seq());
        }
        int[] r = new int[TELEMETRY_COUNT];
        r[COUNTER_HI] = (int) ((s.seq() >>> 16) & 0xFFFF);
        r[COUNTER_LO] = (int) (s.seq() & 0xFFFF);
        r[STATE] = s.state().code();
        // 온도는 영하가 될 수 있으므로 int16(2의 보수)로 넣는다. & 0xFFFF로 레지스터 범위(0~65535)에 맞춘다.
        r[TEMPERATURE] = ((short) Math.round(s.temperature() * 10)) & 0xFFFF;
        r[VIBRATION] = clampU16(Math.round(s.vibration() * 100));
        r[RPM] = clampU16(s.rpm());
        r[ALARM] = clampU16(s.alarmCode());
        return r;
    }

    /** 0~6번 레지스터 값을 해석한다. 배열 길이가 7 미만이면 예외. */
    public static Sample decode(int[] r) {
        if (r.length < TELEMETRY_COUNT) {
            throw new IllegalArgumentException("need " + TELEMETRY_COUNT + " registers, got " + r.length);
        }
        long seq = ((long) (r[COUNTER_HI] & 0xFFFF) << 16) | (r[COUNTER_LO] & 0xFFFF);
        // (short) 캐스팅으로 0x8000 이상을 음수로 되돌린다.
        double temp = ((short) r[TEMPERATURE]) / 10.0;
        double vib = (r[VIBRATION] & 0xFFFF) / 100.0;
        return new Sample(seq, DeviceState.fromCode(r[STATE] & 0xFFFF), temp, vib, r[RPM] & 0xFFFF, r[ALARM] & 0xFFFF);
    }

    /**
     * (포트, unit) → deviceId. 예: basePort 5020, port 5021, unit 7 → B-0107
     */
    public static String deviceId(String factoryId, int basePort, int port, int unit) {
        if (unit < 1 || unit > UNITS_PER_PORT) {
            throw new IllegalArgumentException("unit out of range: " + unit);
        }
        return DeviceIds.of(factoryId, (port - basePort) * UNITS_PER_PORT + unit);
    }

    /** deviceId → 포트 오프셋(0부터). B-0107 → 1 */
    public static int portOffsetOf(String deviceId) {
        return (DeviceIds.numberOf(deviceId) - 1) / UNITS_PER_PORT;
    }

    /** deviceId → unit (1~100). B-0107 → 7 */
    public static int unitOf(String deviceId) {
        return (DeviceIds.numberOf(deviceId) - 1) % UNITS_PER_PORT + 1;
    }

    private static int clampU16(long v) {
        return (int) Math.max(0, Math.min(0xFFFF, v));
    }
}
