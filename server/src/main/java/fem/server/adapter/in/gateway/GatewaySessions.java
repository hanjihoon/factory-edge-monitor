package fem.server.adapter.in.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 게이트웨이 ID → 현재 WebSocket 세션. 서버가 명령을 내려보낼 때 세션을 찾는 데 쓴다.
 * 같은 게이트웨이가 다시 붙으면 옛 세션을 닫는다 (반쯤 죽은 옛 연결로 명령이 가지 않게).
 */
@Component
public class GatewaySessions {

    private static final Logger log = LoggerFactory.getLogger(GatewaySessions.class);
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    void register(String gatewayId, WebSocketSession session) {
        WebSocketSession old = sessions.put(gatewayId, session);
        if (old != null && old != session && old.isOpen()) {
            log.warn("{} reconnected, closing previous session", gatewayId);
            try {
                old.close(CloseStatus.POLICY_VIOLATION.withReason("replaced by new connection"));
            } catch (IOException ignored) {
                // 이미 죽은 연결이면 닫기도 실패한다. 무시해도 된다
            }
        }
    }

    /**
     * 등록된 세션이 이 ID일 때만 지운다. 새 연결이 이미 등록된 뒤 옛 세션의 종료 통지가 늦게 오면 지우지 않는다.
     *
     * @return 지웠으면 true
     */
    boolean unregisterById(String gatewayId, String sessionId) {
        boolean[] removed = {false};
        sessions.computeIfPresent(gatewayId, (id, current) -> {
            if (current.getId().equals(sessionId)) {
                removed[0] = true;
                return null; // null을 돌려주면 맵에서 지워진다
            }
            return current;
        });
        return removed[0];
    }

    /**
     * @return 보냈으면 true. 연결이 없거나 전송이 실패하면 false
     */
    public boolean send(String gatewayId, String text) {
        WebSocketSession s = sessions.get(gatewayId);
        if (s == null || !s.isOpen()) return false;
        try {
            s.sendMessage(new TextMessage(text));
            return true;
        } catch (IOException | IllegalStateException e) {
            log.warn("send to {} failed: {}", gatewayId, e.toString());
            return false;
        }
    }

    public boolean isConnected(String gatewayId) {
        WebSocketSession s = sessions.get(gatewayId);
        return s != null && s.isOpen();
    }
}
