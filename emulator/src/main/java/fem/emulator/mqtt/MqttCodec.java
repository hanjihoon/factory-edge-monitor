package fem.emulator.mqtt;

import fem.common.mqtt.MqttPayloads;
import fem.emulator.device.Reading;
import tools.jackson.databind.json.JsonMapper;

/** MQTT 페이로드 ↔ 바이트 변환. 키 이름은 common의 record 필드 이름 그대로다. */
public final class MqttCodec {

    // JsonMapper는 만든 뒤에는 스레드 안전하다. 하나를 공유한다.
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MqttCodec() {
    }

    public static byte[] telemetry(Reading r) {
        return JSON.writeValueAsBytes(new MqttPayloads.Telemetry(
                r.seq(), r.ts(), r.state().name(), r.temperature(), r.vibration(), r.rpm(), r.alarmCode()));
    }

    public static MqttPayloads.Command readCommand(byte[] payload) {
        return JSON.readValue(payload, MqttPayloads.Command.class);
    }

    public static byte[] commandAck(MqttPayloads.CommandAck ack) {
        return JSON.writeValueAsBytes(ack);
    }

    /** 테스트와 디버깅용 */
    public static <T> T read(byte[] payload, Class<T> type) {
        return JSON.readValue(payload, type);
    }
}
