package fem.server.adapter.in.gateway;

import fem.common.wire.WireMessages.Batch;
import fem.server.application.GatewayPresenceService;
import fem.server.domain.GatewayIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@code /ws/gateway} 엔드포인트.
 *
 * <h2>BATCH는 작업 풀에서 병렬로 저장한다</h2>
 * 한 세션의 메시지는 컨테이너가 순서대로 하나씩 넘겨준다. 예전에는 그 자리에서 저장까지 끝냈기 때문에
 * 한 게이트웨이의 묶음은 한 번에 하나씩만 저장됐다 (게이트웨이당 초당 약 3,500건이 상한, 복구 시간의 병목).
 * 지금은 BATCH를 작업 풀에 넘기고 곧바로 다음 메시지를 읽는다. 저장이 끝나는 순서는 뒤섞이지만 문제없다.
 * <ul>
 *   <li>ack는 "이 범위를 저장했다"(범위 ack)라 순서와 무관하다. 게이트웨이는 보낸 묶음과 정확히 같은 범위만 지운다
 *   <li>중복은 (device_id, seq) 기본 키가, 현재 상태는 "seq가 클 때만 갱신"이 지키므로 저장 순서에 기대지 않는다
 *   <li>여러 트랜잭션이 같은 설비 행을 잠가도 설비 ID 순으로 잠그므로 교착이 없다 ({@link fem.server.domain.LatestPerDevice})
 *   <li>동시에 처리되는 묶음 수는 게이트웨이 전송 창(ack 없이 보낼 수 있는 묶음 수)이 막는다. 풀 크기는 DB 연결 수보다 작게 둔다
 * </ul>
 * 상태 보고·명령 결과는 가벼우므로 받은 스레드에서 바로 처리한다.
 *
 * <p> * 반면 명령(COMMAND)은 다른 스레드(REST 요청, 재시도 스케줄러)에서 보내므로 세션을
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
    /** BATCH 저장 작업 풀. 크기 1이면 예전처럼 하나씩 (비교 측정용) */
    private final ExecutorService ingestPool;

    public GatewayWebSocketHandler(ServerWireCodec codec, GatewayMessageRouter router,
                                   GatewaySessions sessions, GatewayPresenceService presence,
                                   @Value("${fem.ingest.parallelism:4}") int parallelism) {
        this.codec = codec;
        this.router = router;
        this.sessions = sessions;
        this.presence = presence;
        this.ingestPool = Executors.newFixedThreadPool(parallelism, Thread.ofPlatform().name("ingest-", 0).factory());
        log.info("batch ingest parallelism {}", parallelism);
    }

    @PreDestroy
    void shutdown() {
        ingestPool.shutdown(); // 처리 중인 묶음은 끝까지 저장한다. 받기만 하고 ack 못 한 묶음은 게이트웨이가 다시 보낸다
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
        if (decoded instanceof Batch) {
            ingestPool.execute(() -> handle(session, gw, decoded)); // 저장은 풀에서, 이 스레드는 곧바로 다음 메시지로
        } else {
            handle(session, gw, decoded);
        }
    }

    private void handle(WebSocketSession session, GatewayIdentity gw, Object decoded) {
        try {
            // ack는 게이트웨이 ID로 지금 붙어 있는 연결에 보낸다. 저장 중 게이트웨이가 다시 붙었으면 새 연결로 간다.
            // 커밋한 뒤에만 보내므로 어느 연결로 가든 "저장됨"은 사실이다
            router.route(gw, decoded).ifPresent(reply -> sessions.send(gw.gatewayId(), codec.encode(reply)));
        } catch (RuntimeException e) {
            // 저장 실패(DB 장애 등): ack를 보내지 않고 연결을 끊는다. 게이트웨이는 재연결 후 같은 묶음을 다시 보낸다.
            log.error("{} message handling failed, closing to force resend: {}", gw.gatewayId(), e.toString());
            try {
                session.close(CloseStatus.SERVER_ERROR);
            } catch (IOException ignored) {
                // 이미 끊긴 연결이다. 게이트웨이는 ack를 못 받았으니 어차피 다시 보낸다
            }
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
