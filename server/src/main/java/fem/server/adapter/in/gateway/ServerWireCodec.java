package fem.server.adapter.in.gateway;

import fem.common.wire.WireMessages.MessageType;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 게이트웨이 프레임 {@code {"type","body"}} ↔ 메시지 record.
 *
 * <p>게이트웨이의 WireCodec과 같은 규칙이다. common은 JSON 라이브러리에 의존하지 않기로 해서
 * 양쪽에 같은 30줄을 두었다 (중복을 감수하고 common을 의존성 없이 유지).
 * 계약이 어긋나지 않는지는 서버의 종단 테스트가 실제 게이트웨이 코드로 확인한다.
 */
@Component
public class ServerWireCodec {

    private final JsonMapper json;

    public ServerWireCodec(JsonMapper json) {
        // 모르는 enum 값(예: 새 버전 게이트웨이가 보낸 상태 MAINTENANCE)은 null로 읽는다.
        // 예외로 두면 묶음 전체가 해석되지 않아 ack가 안 나가고, 게이트웨이가 같은 묶음을 끝없이 다시 보낸다.
        // null로 읽으면 그 레코드만 RecordCheck에서 걸러져 격리되고 나머지는 저장된다.
        this.json = json.rebuild().enable(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL).build();
    }

    record Frame(String type, JsonNode body) {
    }

    public String encode(Object message) {
        return json.writeValueAsString(new Frame(MessageType.of(message).name(), json.valueToTree(message)));
    }

    public Object decode(String text) {
        try {
            Frame frame = json.readValue(text, Frame.class);
            if (frame.type() == null || frame.body() == null) {
                throw new IllegalArgumentException("frame needs type and body");
            }
            return json.treeToValue(frame.body(), MessageType.valueOf(frame.type()).bodyType());
        } catch (JacksonException e) {
            throw new IllegalArgumentException("malformed frame: " + e.getOriginalMessage(), e);
        }
    }
}
