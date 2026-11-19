package fem.server.application;

import fem.server.application.port.out.CommandRepository;
import fem.server.domain.AuditEntry;
import fem.server.domain.RemoteCommand;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** JDBC 구현과 같은 규칙의 메모리 저장소 (테스트용) */
public class InMemoryCommandRepository implements CommandRepository {

    private final Map<String, RemoteCommand> commands = new LinkedHashMap<>();
    public final List<AuditEntry> audit = new ArrayList<>();

    @Override
    public synchronized boolean insertIfAbsent(RemoteCommand c) {
        return commands.putIfAbsent(c.commandId(), c) == null;
    }

    @Override
    public synchronized Optional<RemoteCommand> find(String commandId) {
        return Optional.ofNullable(commands.get(commandId));
    }

    @Override
    public synchronized void update(RemoteCommand c) {
        commands.put(c.commandId(), c);
    }

    @Override
    public synchronized List<RemoteCommand> findOpen() {
        return commands.values().stream().filter(c -> !c.status().terminal()).toList();
    }

    @Override
    public synchronized List<RemoteCommand> findRecent(int limit) {
        return commands.values().stream().sorted(Comparator.comparingLong(RemoteCommand::createdAt).reversed()).limit(limit).toList();
    }

    @Override
    public synchronized void audit(AuditEntry entry) {
        audit.add(entry);
    }

    @Override
    public synchronized List<AuditEntry> auditOf(String commandId) {
        return audit.stream().filter(a -> a.commandId().equals(commandId)).toList();
    }

    public synchronized List<String> eventsOf(String commandId) {
        return auditOf(commandId).stream().map(AuditEntry::event).toList();
    }
}
