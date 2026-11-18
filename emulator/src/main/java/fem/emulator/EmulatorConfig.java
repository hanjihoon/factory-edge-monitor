package fem.emulator;

import java.util.Map;

/**
 * 에뮬레이터 설정. 모두 환경 변수로 받는다 (docker-compose.yml 참고).
 *
 * @param mqttUrl          공장 A 브로커 주소. 비어 있으면 MQTT 설비를 띄우지 않는다
 * @param mqttDevices      공장 A 설비 수 (A-0001 ~)
 * @param modbusDevices    공장 B 설비 수 (B-0001 ~). 0이면 Modbus를 띄우지 않는다
 * @param modbusBasePort   첫 라인 컨트롤러 포트. 설비 100대마다 포트 하나씩 늘어난다
 * @param duplicateRate    MQTT 메시지 중복 확률
 * @param reorderRate      MQTT 메시지 순서 뒤바뀜 확률
 * @param delayMaxMs       MQTT 발행 지연 최대값
 * @param deviceFaultRate  설비가 1초마다 FAULT로 바뀔 확률
 */
public record EmulatorConfig(
        String mqttUrl,
        int mqttDevices,
        int modbusDevices,
        int modbusBasePort,
        double duplicateRate,
        double reorderRate,
        int delayMaxMs,
        double deviceFaultRate
) {

    public static final String MQTT_FACTORY = "A";
    public static final String MODBUS_FACTORY = "B";

    public static EmulatorConfig from(Map<String, String> env) {
        return new EmulatorConfig(
                env.getOrDefault("MQTT_URL", ""),
                Integer.parseInt(env.getOrDefault("MQTT_DEVICES", "600")),
                Integer.parseInt(env.getOrDefault("MODBUS_DEVICES", "400")),
                Integer.parseInt(env.getOrDefault("MODBUS_BASE_PORT", "5020")),
                Double.parseDouble(env.getOrDefault("FAULT_DUPLICATE_RATE", "0.01")),
                Double.parseDouble(env.getOrDefault("FAULT_REORDER_RATE", "0.01")),
                Integer.parseInt(env.getOrDefault("FAULT_DELAY_MAX_MS", "300")),
                // 600+400대 × 0.0002 ≈ 초당 0.2대 고장 → 화면에 알람이 가끔 보일 정도
                Double.parseDouble(env.getOrDefault("DEVICE_FAULT_RATE", "0.0002")));
    }

    public boolean mqttEnabled() {
        return !mqttUrl.isBlank() && mqttDevices > 0;
    }

    public boolean modbusEnabled() {
        return modbusDevices > 0;
    }
}
