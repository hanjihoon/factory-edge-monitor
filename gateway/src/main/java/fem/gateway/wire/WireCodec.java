package fem.gateway.wire;

import fem.common.wire.WireMessages.MessageType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * WebSocket 프레임 ↔ 메시지 record 변환. 프레임 형식: {@code {"type":"BATCH","body":{...}}}
 *
 * <p>읽을 때는 먼저 {@link Frame}으로 받아 type을 보고, body(JsonNode)를 해당 record로 바꾼다.
 */
public final class WireCodec {

    private final JsonMapper json = JsonMapper.builder().build();

    /** 봉투. body는 type이 정해진 뒤에 변환하므로 JsonNode로 받아 둔다. */
    record Frame(String type, JsonNode body) {
    }

    public String encode(Object message) {
        MessageType type = MessageType.of(message);
        return json.writeValueAsString(new Frame(type.name(), json.valueToTree(message)));
    }

    /**
     * @throws IllegalArgumentException 형식이 잘못됐거나 모르는 type
     */
    public Object decode(String text) {
        try {
            Frame frame = json.readValue(text, Frame.class);
            if (frame.type() == null || frame.body() == null) {
                throw new IllegalArgumentException("frame needs type and body");
            }
            MessageType type = MessageType.valueOf(frame.type());
            return json.treeToValue(frame.body(), type.bodyType());
        } catch (JacksonException e) {
            throw new IllegalArgumentException("malformed frame: " + e.getOriginalMessage(), e);
        }
    }

    /** 레코드 하나를 JSON으로 (outbox 저장용) */
    public String toJson(Object value) {
        return json.writeValueAsString(value);
    }

    public <T> T fromJson(String text, Class<T> type) {
        return json.readValue(text, type);
    }
}
