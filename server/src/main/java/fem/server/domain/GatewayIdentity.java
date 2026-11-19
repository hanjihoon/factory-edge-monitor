package fem.server.domain;

/** 인증된 게이트웨이. WebSocket 세션 속성에 넣어 두고 메시지마다 꺼내 쓴다. */
public record GatewayIdentity(String gatewayId, String factoryId) {
}
