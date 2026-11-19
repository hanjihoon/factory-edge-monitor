package fem.server.application.port.in;

import fem.common.wire.WireMessages.CommandResult;

/** 게이트웨이가 보낸 명령 실행 결과 수신 (입력 포트). 구현은 5단계 CommandService */
public interface CommandResultUseCase {

    void onResult(String gatewayId, CommandResult result);
}
