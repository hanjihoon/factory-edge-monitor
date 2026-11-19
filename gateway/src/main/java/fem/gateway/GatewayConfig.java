package fem.gateway;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

/**
 * 게이트웨이 설정. 모두 환경 변수로 받는다 (docker-compose.yml 참고).
 *
 * @param source         수집 방식: mqtt(공장 A) 또는 modbus(공장 B)
 * @param bufferMaxRows  디스크 버퍼 상한. 기본 = 600대 × 6시간(21,600초) (설계 가정 7)
 * @param batchSize      BATCH 한 번에 담는 레코드 수
 * @param window         ack 없이 연달아 보낼 수 있는 BATCH 수
 * @param ackTimeoutMs   가장 오래된 미확인 BATCH가 이 시간을 넘으면 재연결
 */
public record GatewayConfig(
        String gatewayId,
        String factoryId,
        String token,
        String source,
        String mqttUrl,
        String modbusHost,
        int modbusBasePort,
        int modbusDevices,
        long modbusPollMs,
        URI serverUri,
        Path bufferPath,
        long bufferMaxRows,
        int batchSize,
        int window,
        long ackTimeoutMs,
        long commandTimeoutMs
) {

    public static GatewayConfig from(Map<String, String> env) {
        String source = env.getOrDefault("SOURCE", "mqtt").toLowerCase();
        if (!source.equals("mqtt") && !source.equals("modbus")) {
            throw new IllegalArgumentException("SOURCE must be mqtt or modbus: " + source);
        }
        return new GatewayConfig(
                required(env, "GATEWAY_ID"),
                required(env, "FACTORY_ID"),
                required(env, "GATEWAY_TOKEN"),
                source,
                env.getOrDefault("MQTT_URL", "tcp://localhost:1883"),
                env.getOrDefault("MODBUS_HOST", "localhost"),
                Integer.parseInt(env.getOrDefault("MODBUS_BASE_PORT", "5020")),
                Integer.parseInt(env.getOrDefault("MODBUS_DEVICES", "400")),
                Long.parseLong(env.getOrDefault("MODBUS_POLL_MS", "500")),
                URI.create(env.getOrDefault("SERVER_WS_URL", "ws://localhost:8080/ws/gateway")),
                Path.of(env.getOrDefault("BUFFER_PATH", "buffer.db")),
                Long.parseLong(env.getOrDefault("BUFFER_MAX_ROWS", String.valueOf(600L * 21_600))),
                // 2,000: 서버가 묶음마다 내는 고정 비용(문장 실행·커밋·설비 현재 상태 갱신 최대 600행)을
                // 더 많은 건수로 나눠 적체 소진을 빠르게 한다. 레코드 JSON 약 250B × 2,000 ≈ 0.5MB로
                // 서버 메시지 한도(2MB, WebSocketConfig.MAX_MESSAGE_BYTES) 안이다. (설계 문서 10장 측정 근거)
                Integer.parseInt(env.getOrDefault("BATCH_SIZE", "2000")),
                Integer.parseInt(env.getOrDefault("WINDOW", "8")),
                Long.parseLong(env.getOrDefault("ACK_TIMEOUT_MS", "15000")),
                Long.parseLong(env.getOrDefault("COMMAND_TIMEOUT_MS", "3000")));
    }

    private static String required(Map<String, String> env, String key) {
        String v = env.get(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException(key + " is required");
        return v;
    }

    /** 로그에 토큰이 찍히지 않게 가린다 */
    @Override
    public String toString() {
        return "GatewayConfig[id=%s, factory=%s, source=%s, server=%s, buffer=%s(max %d), batch=%d, window=%d]"
                .formatted(gatewayId, factoryId, source, serverUri, bufferPath, bufferMaxRows, batchSize, window);
    }
}
