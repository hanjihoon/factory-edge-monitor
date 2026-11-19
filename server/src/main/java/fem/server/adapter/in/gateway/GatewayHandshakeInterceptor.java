package fem.server.adapter.in.gateway;

import fem.server.application.GatewayAuthService;
import fem.server.domain.GatewayIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.Optional;

/**
 * 게이트웨이 핸드셰이크 인증. 토큰이 틀리면 WebSocket으로 올라가기 전에 401로 끊는다.
 * 통과하면 신원을 세션 속성에 넣어 메시지 처리 때 꺼내 쓴다.
 */
@Component
public class GatewayHandshakeInterceptor implements HandshakeInterceptor {

    static final String IDENTITY = "gateway.identity";
    private static final Logger log = LoggerFactory.getLogger(GatewayHandshakeInterceptor.class);

    private final GatewayAuthService auth;

    public GatewayHandshakeInterceptor(GatewayAuthService auth) {
        this.auth = auth;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String gatewayId = request.getHeaders().getFirst("X-Gateway-Id");
        String authorization = request.getHeaders().getFirst("Authorization");
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;

        Optional<GatewayIdentity> identity = auth.authenticate(gatewayId, token);
        if (identity.isEmpty()) {
            log.warn("gateway handshake rejected: id={} from {}", gatewayId, request.getRemoteAddress());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(IDENTITY, identity.get());
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }
}
