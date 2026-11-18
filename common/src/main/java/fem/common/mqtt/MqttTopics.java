package fem.common.mqtt;

/**
 * 공장 A MQTT 토픽 규칙.
 * <pre>
 *   factory/{factoryId}/device/{deviceId}/telemetry     설비 → 게이트웨이 (QoS 1)
 *   factory/{factoryId}/device/{deviceId}/command       게이트웨이 → 설비 (QoS 1)
 *   factory/{factoryId}/device/{deviceId}/command/ack   설비 → 게이트웨이 (QoS 1)
 * </pre>
 */
public final class MqttTopics {

    private MqttTopics() {
    }

    public static String telemetry(String factoryId, String deviceId) {
        return base(factoryId, deviceId) + "/telemetry";
    }

    public static String command(String factoryId, String deviceId) {
        return base(factoryId, deviceId) + "/command";
    }

    public static String commandAck(String factoryId, String deviceId) {
        return base(factoryId, deviceId) + "/command/ack";
    }

    /** 게이트웨이가 구독할 공장 전체 텔레메트리. {@code +}는 한 단계 와일드카드 */
    public static String allTelemetry(String factoryId) {
        return "factory/" + factoryId + "/device/+/telemetry";
    }

    public static String allCommands(String factoryId) {
        return "factory/" + factoryId + "/device/+/command";
    }

    public static String allCommandAcks(String factoryId) {
        return "factory/" + factoryId + "/device/+/command/ack";
    }

    /**
     * 토픽에서 deviceId를 꺼낸다. 형식이 맞지 않으면 예외.
     * 예: {@code factory/A/device/A-0001/telemetry} → {@code A-0001}
     */
    public static String deviceIdOf(String topic) {
        String[] parts = topic.split("/");
        if (parts.length < 5 || !"factory".equals(parts[0]) || !"device".equals(parts[2])) {
            throw new IllegalArgumentException("unexpected topic: " + topic);
        }
        return parts[3];
    }

    private static String base(String factoryId, String deviceId) {
        return "factory/" + factoryId + "/device/" + deviceId;
    }
}
