package fem.server.application.port.in;

import fem.common.wire.WireMessages.CommandAction;
import fem.server.domain.AuditEntry;
import fem.server.domain.RemoteCommand;

import java.util.List;

/** 원격 명령 요청·조회 (입력 포트) */
public interface IssueCommandUseCase {

    /**
     * 명령을 접수하고 게이트웨이로 보낸다. 결과는 나중에 비동기로 온다.
     * 같은 commandId로 다시 요청하면 새로 만들지 않고 기존 명령을 돌려준다 (멱등).
     *
     * @throws IllegalArgumentException 모르는 설비
     * @throws IllegalStateException    같은 commandId로 다른 설비·동작을 요청
     */
    RemoteCommand issue(String commandId, String deviceId, CommandAction action, String requestedBy);

    List<RemoteCommand> recent(int limit);

    List<AuditEntry> audit(String commandId);
}
