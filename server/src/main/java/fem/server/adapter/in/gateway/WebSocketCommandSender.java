package fem.server.adapter.in.gateway;

import fem.common.wire.WireMessages.Command;
import fem.server.application.port.out.GatewayCommandSender;
import org.springframework.stereotype.Component;

/** 명령을 게이트웨이 WebSocket 세션으로 내려보낸다 (게이트웨이가 연 연결을 거꾸로 탄다). */
@Component
public class WebSocketCommandSender implements GatewayCommandSender {

    private final GatewaySessions sessions;
    private final ServerWireCodec codec;

    public WebSocketCommandSender(GatewaySessions sessions, ServerWireCodec codec) {
        this.sessions = sessions;
        this.codec = codec;
    }

    @Override
    public boolean send(String gatewayId, Command command) {
        return sessions.send(gatewayId, codec.encode(command));
    }
}
