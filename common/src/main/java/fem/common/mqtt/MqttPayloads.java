package fem.common.mqtt;

/**
 * 공장 A 설비가 MQTT로 주고받는 JSON 페이로드 모양.
 * 필드 이름이 곧 JSON 키다. (설계 문서 6장 매핑표)
 *
 * <p>common은 JSON 라이브러리에 의존하지 않으므로 여기엔 record만 두고,
 * 직렬화는 에뮬레이터·게이트웨이가 각자 Jackson으로 한다. record 필드 이름을 그대로 쓰므로 키가 어긋날 일이 없다.
 */
public final class MqttPayloads {

    private MqttPayloads() {
    }

    /**
     * 텔레메트리. 예: {@code {"seq":42,"ts":1763452800000,"state":"RUNNING","temp":61.5,"vib":2.31,"rpm":1480,"alarm":0}}
     *
     * @param state {@link fem.common.DeviceState} 이름 문자열
     */
    public record Telemetry(long seq, long ts, String state, double temp, double vib, int rpm, int alarm) {
    }

    /** 명령. 예: {@code {"commandId":"c-1","action":"STOP"}} */
    public record Command(String commandId, String action) {
    }

    /**
     * 명령 응답.
     *
     * @param outcome {@link fem.common.wire.WireMessages.CommandOutcome} 이름
     * @param state   명령 처리 후 설비 상태
     */
    public record CommandAck(String commandId, String outcome, String state, long ts) {
    }
}
