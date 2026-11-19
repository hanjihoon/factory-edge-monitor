package fem.server.adapter.in.gateway;

import fem.server.application.GatewayPresenceService;
import fem.server.domain.GatewayIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * {@code /ws/gateway} 엔드포인트.
 *
 * <p>한 세션의 메시지는 컨테이너가 순서대로 하나씩 넘겨준다. 그래서 BATCH 저장 → ACK도 순서대로 나간다.
 * 반면 명령(COMMAND)은 다른 스레드(REST 요청, 재시도 스케줄러)에서 보내므로 세션을
 * {@link ConcurrentWebSocketSessionDecorator}로 감싸 동시 전송을 안전하게 만든다.
 */
@Component
public class GatewayWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayWebSocketHandler.class);
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_LIMIT = 4 * 1024 * 1024;

    private final ServerWireCodec codec;
    private final GatewayMessageRouter router;
    private final GatewaySessions sessions;
    private final GatewayPresenceService presence;

    public GatewayWebSocketHandler(ServerWireCodec codec, GatewayMessageRouter router,
                                   GatewaySessions sessions, GatewayPresenceService presence) {
        this.codec = codec;
        this.router = router;
        this.sessions = sessions;
        this.presence = presence;
    }

    private static GatewayIdentity identity(WebSocketSession session) {
        return (GatewayIdentity) session.getAttributes().get(GatewayHandshakeInterceptor.IDENTITY);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        GatewayIdentity gw = identity(session);
        sessions.register(gw.gatewayId(), new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT));
        presence.connected(gw.gatewayId());
        log.info("gateway {} connected from {}", gw.gatewayId(), session.getRemoteAddress());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        GatewayIdentity gw = identity(session);
        Object decoded;
        try {
            decoded = codec.decode(message.getPayload());
        } catch (IllegalArgumentException e) {
            log.warn("{} sent malformed frame: {}", gw.gatewayId(), e.getMessage());
            return;
        }
        try {
            router.route(gw, decoded).ifPresent(reply -> sessions.send(gw.gatewayId(), codec.encode(reply)));
        } catch (RuntimeException e) {
            // 저장 실패(DB 장애 등): ack를 보내지 않고 연결을 끊는다. 게이트웨이는 재연결 후 같은 묶음을 다시 보낸다.
            log.error("{} message handling failed, closing to force resend: {}", gw.gatewayId(), e.toString());
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        GatewayIdentity gw = identity(session);
        // 세션 레지스트리에는 데코레이터가 들어 있으므로 원래 세션 ID로 비교해야 한다
        if (sessions.unregisterById(gw.gatewayId(), session.getId())) {
            presence.disconnected(gw.gatewayId());
        }
        log.info("gateway {} disconnected: {}", gw.gatewayId(), status);
    }
}
