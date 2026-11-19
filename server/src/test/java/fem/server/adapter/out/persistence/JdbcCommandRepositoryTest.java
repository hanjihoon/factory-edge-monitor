package fem.server.adapter.out.persistence;

import fem.common.wire.WireMessages.CommandAction;
import fem.common.wire.WireMessages.CommandOutcome;
import fem.server.domain.AuditEntry;
import fem.server.domain.RemoteCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 명령 멱등성(commandId 기본 키)과 상태 저장·복원, 감사 로그 순서를 실제 DB로 확인한다. */
class JdbcCommandRepositoryTest extends TimescaleDbSupport {

    private JdbcCommandRepository repo;

    @BeforeEach
    void setUp() {
        repo = new JdbcCommandRepository(JdbcClient.create(dataSource));
    }

    private static RemoteCommand stop(String id) {
        return new RemoteCommand(id, "A-0001", "gw-a", CommandAction.STOP, "operator", 1_000);
    }

    @Test
    void same_command_id_is_inserted_only_once() {
        // UI가 두 번 눌렀거나 HTTP를 재전송해도 명령은 하나
        assertTrue(repo.insertIfAbsent(stop("c-1")));
        assertFalse(repo.insertIfAbsent(stop("c-1")));

        assertEquals(1, count("SELECT count(*) FROM command"));
    }

    @Test
    void state_changes_survive_a_round_trip_through_the_database() {
        RemoteCommand c = stop("c-2");
        repo.insertIfAbsent(c);
        c.markSent(2_000);
        c.applyResult(CommandOutcome.APPLIED, "stopped", 2_500);
        repo.update(c);

        RemoteCommand loaded = repo.find("c-2").orElseThrow();

        assertEquals(RemoteCommand.Status.SUCCEEDED, loaded.status());
        assertEquals(CommandOutcome.APPLIED, loaded.outcome());
        assertEquals(1, loaded.attempts());
        assertEquals(2_000, loaded.lastSentAt());
        assertEquals(2_500, loaded.completedAt());
        assertEquals("stopped", loaded.detail());
    }

    @Test
    void pending_command_without_outcome_is_read_back_with_null_outcome() {
        repo.insertIfAbsent(stop("c-3"));

        RemoteCommand loaded = repo.find("c-3").orElseThrow();

        assertEquals(RemoteCommand.Status.PENDING, loaded.status());
        assertNull(loaded.outcome());
    }

    @Test
    void only_unfinished_commands_are_open() {
        repo.insertIfAbsent(stop("open-pending"));
        RemoteCommand sent = stop("open-sent");
        repo.insertIfAbsent(sent);
        sent.markSent(2_000);
        repo.update(sent);
        RemoteCommand done = stop("done");
        repo.insertIfAbsent(done);
        done.markSent(2_000);
        done.applyResult(CommandOutcome.APPLIED, null, 2_100);
        repo.update(done);

        assertEquals(List.of("open-pending", "open-sent"),
                repo.findOpen().stream().map(RemoteCommand::commandId).sorted().toList());
    }

    @Test
    void audit_entries_come_back_in_the_order_they_were_written() {
        // 같은 밀리초에 여러 건이 써져도 순서가 유지돼야 한다 (id 순 정렬)
        repo.audit(new AuditEntry("c-4", 5_000, "REQUESTED", "operator", null));
        repo.audit(new AuditEntry("c-4", 5_000, "SENT", "server", "attempt 1"));
        repo.audit(new AuditEntry("c-4", 5_000, "RESULT", "gw-a", "APPLIED"));
        repo.audit(new AuditEntry("other", 5_000, "REQUESTED", "operator", null));

        assertEquals(List.of("REQUESTED", "SENT", "RESULT"),
                repo.auditOf("c-4").stream().map(AuditEntry::event).toList());
    }
}
