package fem.server.adapter.in.ui;

import fem.server.adapter.in.web.ApiController.CommandView;
import fem.server.application.GatewayPresenceService;
import fem.server.application.UserAuthService;
import fem.server.application.port.in.IssueCommandUseCase;
import fem.server.application.port.out.TelemetryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

/**
 * {@code /ws/ui?token=...} 관제 화면 실시간 연결. 서버 → 화면 단방향이다 (명령은 REST로 보낸다).
 *
 * <p>브라우저 WebSocket API는 요청 헤더를 붙일 수 없어 토큰을 쿼리 파라미터로 받는다.
 * 한계: 프록시 접근 로그에 토큰이 남을 수 있다 → 설계 문서 12장.
 */
@Component
public class UiWebSocketHandler extends TextWebSocketHandler implements HandshakeInterceptor {

    private final UiSessions sessions;
    private final UserAuthService auth;
    private final TelemetryRepository telemetry;
    private final GatewayPresenceService gateways;
    private final IssueCommandUseCase commands;
    private final JsonMapper json;

    public UiWebSocketHandler(UiSessions sessions, UserAuthService auth, TelemetryRepository telemetry,
                              GatewayPresenceService gateways, IssueCommandUseCase commands, JsonMapper json) {
        this.sessions = sessions;
        this.auth = auth;
        this.telemetry = telemetry;
        this.gateways = gateways;
        this.commands = commands;
        this.json = json;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String token = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("token");
        if (auth.verify(token).isEmpty()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    /** 접속 직후 전체 상태를 한 번 보낸다. 이후로는 변경분만 간다. */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        sessions.send(session, json.writeValueAsString(Map.<String, Object>of(
                "type", "snapshot",
                "devices", telemetry.findAllCurrent(),
                "gateways", gateways.all(),
                "commands", commands.recent(50).stream().map(CommandView::of).toList(),
                "serverTime", System.currentTimeMillis())));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }
}
