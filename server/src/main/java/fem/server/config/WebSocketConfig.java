package fem.server.config;

import fem.server.adapter.in.gateway.GatewayHandshakeInterceptor;
import fem.server.adapter.in.gateway.GatewayWebSocketHandler;
import fem.server.adapter.in.ui.UiWebSocketHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
@EnableConfigurationProperties(FemProperties.class)
public class WebSocketConfig implements WebSocketConfigurer {

    /**
     * 메시지 크기 상한. Tomcat 기본값은 8KB라 500건짜리 BATCH(약 125KB)가 들어오면
     * 1009(메시지 너무 큼)로 연결이 끊긴다. 여유 있게 2MB.
     */
    static final int MAX_MESSAGE_BYTES = 2 * 1024 * 1024;

    private final GatewayWebSocketHandler gatewayHandler;
    private final GatewayHandshakeInterceptor gatewayAuth;
    private final UiWebSocketHandler uiHandler;

    public WebSocketConfig(GatewayWebSocketHandler gatewayHandler, GatewayHandshakeInterceptor gatewayAuth,
                           UiWebSocketHandler uiHandler) {
        this.gatewayHandler = gatewayHandler;
        this.gatewayAuth = gatewayAuth;
        this.uiHandler = uiHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(gatewayHandler, "/ws/gateway").addInterceptors(gatewayAuth);
        // UI는 nginx가 같은 주소로 프록시하므로(설계 결정 12) 브라우저 Origin 검사는 기본값(같은 출처만)을 그대로 쓴다
        registry.addHandler(uiHandler, "/ws/ui").addInterceptors(uiHandler);
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_MESSAGE_BYTES);
        container.setMaxSessionIdleTimeout(120_000L); // 게이트웨이는 5초마다 상태를 보내므로 2분 무소식이면 죽은 연결
        return container;
    }
}
