package fem.gateway.command;

import fem.common.wire.WireMessages.CommandAction;
import fem.common.wire.WireMessages.CommandOutcome;

/** 설비에 명령을 실제로 전달하는 프로토콜별 구현 (MQTT, Modbus) */
public interface CommandExecutor {

    record Result(CommandOutcome outcome, String detail) {
    }

    /** 블로킹 호출. 설비 응답(또는 시간 초과)까지 기다린다. 실패는 예외 대신 결과로 돌려준다. */
    Result execute(String commandId, String deviceId, CommandAction action) throws InterruptedException;
}
