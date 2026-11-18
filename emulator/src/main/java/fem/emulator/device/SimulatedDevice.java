package fem.emulator.device;

import fem.common.DeviceState;
import fem.common.wire.WireMessages.CommandAction;
import fem.common.wire.WireMessages.CommandOutcome;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * 설비 한 대의 상태와 측정값을 흉내 낸다. 프로토콜(MQTT/Modbus)과 무관한 순수 로직이다.
 *
 * <p>스레드 안전: 측정 스레드({@link #tick})와 명령 처리 스레드({@link #handle})가 동시에 부를 수 있어
 * 공개 메서드를 모두 synchronized로 둔다. 설비 한 대당 호출 빈도가 초당 1~2회라 경합은 무시할 수준이다.
 *
 * <h2>상태 전이</h2>
 * <pre>
 *  RUNNING ──(무작위 고장)──▶ FAULT ──(일정 시간 후 자동 복구)──▶ RUNNING
 *     ▲  │                      │
 *     │  └──── STOP ──▶ STOPPED ◀┘ STOP
 *     └──────── RESUME ─────┘ (FAULT에서 RESUME도 RUNNING으로 = 운영자 리셋)
 * </pre>
 */
public final class SimulatedDevice {

    /** 같은 commandId를 기억하는 개수. 재시도는 보통 수 초 안에 오므로 최근 것만 있으면 된다. */
    private static final int REMEMBERED_COMMANDS = 256;
    private static final int[] FAULT_ALARMS = {101, 102, 103}; // 과열, 과진동, 모터 과부하 (가정)

    private final String deviceId;
    private final RandomGenerator random;
    private final double faultRatePerTick;

    private long seq;
    private DeviceState state = DeviceState.RUNNING;
    private int alarmCode;
    private int faultTicksLeft;
    private double temperature;
    private double vibration;
    // 정수로 들고 있으면 매 tick 반올림 때문에 0 근처에서 멈춘다(2 - 0.4 = 1.6 → 2). 내부는 double, 내보낼 때만 반올림
    private double rpm;

    /** commandId → 처음 처리했을 때의 결과. 재시도에는 같은 결과를 그대로 돌려준다 (멱등). */
    private final Map<String, CommandOutcome> processed = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CommandOutcome> eldest) {
            return size() > REMEMBERED_COMMANDS;
        }
    };

    /**
     * @param initialSeq       첫 tick 직전 seq. 첫 측정은 initialSeq + 1
     * @param faultRatePerTick tick마다 RUNNING → FAULT로 바뀔 확률
     */
    public SimulatedDevice(String deviceId, long initialSeq, double faultRatePerTick, RandomGenerator random) {
        this.deviceId = deviceId;
        this.seq = initialSeq;
        this.faultRatePerTick = faultRatePerTick;
        this.random = random;
        // 설비마다 조금씩 다른 값에서 시작해야 화면에서 구분이 된다.
        this.temperature = 55 + random.nextDouble() * 10;
        this.vibration = 1.5 + random.nextDouble();
        this.rpm = 1450 + random.nextInt(100);
    }

    public String deviceId() {
        return deviceId;
    }

    /** 1초에 한 번 호출. seq를 1 올리고 상태에 맞게 측정값을 움직인다. */
    public synchronized Reading tick(long nowMs) {
        seq++;
        maybeChangeFaultState();
        switch (state) {
            case RUNNING -> drift(65, 2.0, 1500);
            case STOPPED -> drift(25, 0.05, 0);
            case FAULT -> drift(95, 8.0, 300);
        }
        return snapshot(nowMs);
    }

    /**
     * 원격 명령 처리.
     * <ul>
     *   <li>같은 commandId가 다시 오면 상태를 건드리지 않고 처음 결과를 돌려준다.</li>
     *   <li>이미 목표 상태면 {@link CommandOutcome#ALREADY_IN_STATE}.</li>
     * </ul>
     * 두 장치 덕분에 게이트웨이·서버가 몇 번을 재시도해도 설비에는 한 번만 적용된다.
     */
    public synchronized CommandOutcome handle(String commandId, CommandAction action) {
        CommandOutcome previous = processed.get(commandId);
        if (previous != null) {
            return previous;
        }
        CommandOutcome outcome = apply(action);
        processed.put(commandId, outcome);
        return outcome;
    }

    /** commandId가 없는 경로(Modbus 제어 레지스터)용. 상태 기준 멱등만 보장한다. */
    public synchronized CommandOutcome apply(CommandAction action) {
        DeviceState target = action == CommandAction.STOP ? DeviceState.STOPPED : DeviceState.RUNNING;
        if (state == target) {
            return CommandOutcome.ALREADY_IN_STATE;
        }
        state = target;
        alarmCode = 0;
        faultTicksLeft = 0;
        return CommandOutcome.APPLIED;
    }

    public synchronized DeviceState state() {
        return state;
    }

    /** 현재 값을 seq 증가 없이 돌려준다. (명령 직후 레지스터를 바로 갱신할 때 사용) */
    public synchronized Reading current(long nowMs) {
        return snapshot(nowMs);
    }

    private void maybeChangeFaultState() {
        if (state == DeviceState.FAULT) {
            if (--faultTicksLeft <= 0) { // 자동 복구
                state = DeviceState.RUNNING;
                alarmCode = 0;
            }
        } else if (state == DeviceState.RUNNING && random.nextDouble() < faultRatePerTick) {
            state = DeviceState.FAULT;
            alarmCode = FAULT_ALARMS[random.nextInt(FAULT_ALARMS.length)];
            faultTicksLeft = 10 + random.nextInt(21); // 10~30초 지속
        }
    }

    /** 목표값으로 20%씩 다가가고 약간의 잡음을 더한다. 값이 갑자기 튀지 않게 하기 위해서다. */
    private void drift(double targetTemp, double targetVib, int targetRpm) {
        temperature += (targetTemp - temperature) * 0.2 + gaussian(0.3);
        vibration = Math.max(0, vibration + (targetVib - vibration) * 0.2 + gaussian(0.05));
        rpm = Math.max(0, rpm + (targetRpm - rpm) * 0.2 + (targetRpm == 0 ? 0 : gaussian(5)));
    }

    private double gaussian(double sigma) {
        return random.nextGaussian() * sigma;
    }

    private Reading snapshot(long nowMs) {
        return new Reading(deviceId, seq, nowMs, state, round(temperature, 1), round(vibration, 2), (int) Math.round(rpm), alarmCode);
    }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }
}
