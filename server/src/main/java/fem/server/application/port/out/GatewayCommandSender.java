package fem.server.application.port.out;

import fem.common.wire.WireMessages.Command;

/** 게이트웨이로 명령을 내려보내는 출력 포트. 구현은 게이트웨이 WebSocket 세션 */
public interface GatewayCommandSender {

    /** @return 연결이 있어 보냈으면 true. 설비 적용 여부가 아니라 "게이트웨이까지 보냈는가"다 */
    boolean send(String gatewayId, Command command);
}
