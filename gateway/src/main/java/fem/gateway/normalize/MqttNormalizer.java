package fem.gateway.normalize;

import fem.common.DeviceIds;
import fem.common.DeviceState;
import fem.common.Protocol;
import fem.common.TelemetryRecord;
import fem.common.mqtt.MqttPayloads;
import fem.common.mqtt.MqttTopics;
import fem.gateway.wire.WireCodec;

/**
 * 공장 A MQTT 텔레메트리 → 공통 모델 (설계 문서 6장 MQTT 매핑표).
 *
 * <p>중복·순서 뒤바뀜은 여기서 거르지 않는다. 그대로 서버로 보내고 서버가 (deviceId, seq)로 처리한다.
 * 게이트웨이가 재시작하면 "마지막 seq" 기억이 사라지므로 어차피 서버가 최종 판단해야 하기 때문이다.
 */
public final class MqttNormalizer {

    private final String factoryId;
    private final WireCodec codec;

    public MqttNormalizer(String factoryId, WireCodec codec) {
        this.factoryId = factoryId;
        this.codec = codec;
    }

    /**
     * @throws IllegalArgumentException 토픽·페이로드 형식이 틀렸거나 다른 공장 설비
     */
    public TelemetryRecord normalize(String topic, byte[] payload, long gatewayTs) {
        String deviceId = MqttTopics.deviceIdOf(topic);
        if (!factoryId.equals(DeviceIds.factoryOf(deviceId))) {
            throw new IllegalArgumentException("device " + deviceId + " is not in factory " + factoryId);
        }
        MqttPayloads.Telemetry t;
        try {
            t = codec.fromJson(new String(payload, java.nio.charset.StandardCharsets.UTF_8), MqttPayloads.Telemetry.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("malformed telemetry from " + deviceId + ": " + e.getMessage(), e);
        }
        if (t.seq() <= 0 || t.state() == null) {
            throw new IllegalArgumentException("telemetry from " + deviceId + " lacks seq/state");
        }
        return new TelemetryRecord(deviceId, factoryId, Protocol.MQTT, t.seq(), t.ts(), gatewayTs,
                DeviceState.valueOf(t.state()), t.temp(), t.vib(), t.rpm(), t.alarm());
    }
}
