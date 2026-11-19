package fem.server.application.port.out;

import fem.server.domain.AuditEntry;
import fem.server.domain.RemoteCommand;

import java.util.List;
import java.util.Optional;

/** 원격 명령과 감사 로그 저장소 (출력 포트). 구현: JdbcCommandRepository */
public interface CommandRepository {

    /** @return 새로 넣었으면 true, 같은 commandId가 이미 있으면 false */
    boolean insertIfAbsent(RemoteCommand command);

    Optional<RemoteCommand> find(String commandId);

    void update(RemoteCommand command);

    /** 아직 끝나지 않은 명령 (재시도·시간 초과 검사 대상) */
    List<RemoteCommand> findOpen();

    List<RemoteCommand> findRecent(int limit);

    /** 감사 로그는 추가만 한다. 수정·삭제 메서드를 두지 않는다 */
    void audit(AuditEntry entry);

    List<AuditEntry> auditOf(String commandId);
}
