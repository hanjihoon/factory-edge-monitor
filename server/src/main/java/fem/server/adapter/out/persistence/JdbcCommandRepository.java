package fem.server.adapter.out.persistence;

import fem.common.wire.WireMessages.CommandAction;
import fem.common.wire.WireMessages.CommandOutcome;
import fem.server.application.port.out.CommandRepository;
import fem.server.domain.AuditEntry;
import fem.server.domain.RemoteCommand;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcCommandRepository implements CommandRepository {

    private static final String COLUMNS = """
            command_id, device_id, gateway_id, action, requested_by, created_at,
            status, attempts, last_sent_at, completed_at, outcome, detail""";

    private final JdbcClient jdbc;

    public JdbcCommandRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(RemoteCommand c) {
        // 동시에 같은 commandId가 들어와도 기본 키 충돌로 하나만 들어간다
        return jdbc.sql("INSERT INTO command (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING")
                .params(c.commandId(), c.deviceId(), c.gatewayId(), c.action().name(), c.requestedBy(), c.createdAt(),
                        c.status().name(), c.attempts(), c.lastSentAt(), c.completedAt(),
                        c.outcome() == null ? null : c.outcome().name(), c.detail())
                .update() == 1;
    }

    @Override
    public Optional<RemoteCommand> find(String commandId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM command WHERE command_id = ?")
                .param(commandId).query(JdbcCommandRepository::map).optional();
    }

    @Override
    public void update(RemoteCommand c) {
        jdbc.sql("""
                        UPDATE command SET status = ?, attempts = ?, last_sent_at = ?, completed_at = ?, outcome = ?, detail = ?
                        WHERE command_id = ?""")
                .params(c.status().name(), c.attempts(), c.lastSentAt(), c.completedAt(),
                        c.outcome() == null ? null : c.outcome().name(), c.detail(), c.commandId())
                .update();
    }

    @Override
    public List<RemoteCommand> findOpen() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM command WHERE status IN ('PENDING', 'SENT')")
                .query(JdbcCommandRepository::map).list();
    }

    @Override
    public List<RemoteCommand> findRecent(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM command ORDER BY created_at DESC LIMIT ?")
                .param(limit).query(JdbcCommandRepository::map).list();
    }

    @Override
    public void audit(AuditEntry e) {
        jdbc.sql("INSERT INTO command_audit (command_id, at, event, actor, detail) VALUES (?,?,?,?,?)")
                .params(e.commandId(), e.at(), e.event(), e.actor(), e.detail())
                .update();
    }

    @Override
    public List<AuditEntry> auditOf(String commandId) {
        return jdbc.sql("SELECT command_id, at, event, actor, detail FROM command_audit WHERE command_id = ? ORDER BY id")
                .param(commandId)
                .query((rs, i) -> new AuditEntry(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5)))
                .list();
    }

    private static RemoteCommand map(ResultSet rs, int row) throws SQLException {
        String outcome = rs.getString("outcome");
        return new RemoteCommand(rs.getString("command_id"), rs.getString("device_id"), rs.getString("gateway_id"),
                CommandAction.valueOf(rs.getString("action")), rs.getString("requested_by"), rs.getLong("created_at"),
                RemoteCommand.Status.valueOf(rs.getString("status")), rs.getInt("attempts"), rs.getLong("last_sent_at"),
                rs.getLong("completed_at"), outcome == null ? null : CommandOutcome.valueOf(outcome), rs.getString("detail"));
    }
}
