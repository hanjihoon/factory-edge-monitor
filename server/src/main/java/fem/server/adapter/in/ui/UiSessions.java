package fem.server.adapter.in.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 접속 중인 관제 화면들. 변경 사항을 모두에게 뿌린다. */
@Component
public class UiSessions {

    private static final Logger log = LoggerFactory.getLogger(UiSessions.class);
    /** 느린 브라우저 하나 때문에 서버 메모리가 계속 쌓이지 않게: 보내기 10초 또는 대기 1MB를 넘으면 그 세션을 끊는다 */
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int BUFFER_LIMIT = 1024 * 1024;

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    void add(WebSocketSession session) {
        sessions.put(session.getId(), new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_LIMIT));
    }

    void remove(WebSocketSession session) {
        sessions.remove(session.getId());
    }

    void send(WebSocketSession session, String text) {
        WebSocketSession s = sessions.get(session.getId());
        if (s != null) sendTo(s, text);
    }

    public void broadcast(String text) {
        sessions.values().forEach(s -> sendTo(s, text));
    }

    public int count() {
        return sessions.size();
    }

    private void sendTo(WebSocketSession s, String text) {
        if (!s.isOpen()) {
            sessions.remove(s.getId());
            return;
        }
        try {
            s.sendMessage(new TextMessage(text));
        } catch (IOException | RuntimeException e) {
            log.debug("ui send failed ({}), dropping session", e.toString());
            sessions.remove(s.getId());
        }
    }
}
