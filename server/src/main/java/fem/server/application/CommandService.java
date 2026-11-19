package fem.server.application;

import fem.common.DeviceIds;
import fem.common.wire.WireMessages.Command;
import fem.common.wire.WireMessages.CommandAction;
import fem.common.wire.WireMessages.CommandResult;
import fem.server.application.port.in.CommandResultUseCase;
import fem.server.application.port.in.IssueCommandUseCase;
import fem.server.application.port.out.CommandRepository;
import fem.server.application.port.out.GatewayCommandSender;
import fem.server.application.port.out.LiveUpdates;
import fem.server.config.FemProperties;
import fem.server.domain.AuditEntry;
import fem.server.domain.RemoteCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

/**
 * 원격 명령: 접수 → 게이트웨이 전송 → 결과 반영, 응답 없으면 같은 commandId로 재시도.
 *
 * <h2>동시성</h2>
 * 결과 수신(WebSocket 스레드), 재시도 검사(스케줄러), 접수(REST)가 같은 명령을 동시에 건드릴 수 있다.
 * 명령은 초당 수 건 수준이라 잠금 하나로 모든 상태 변경을 직렬화한다 (단순함 우선).
 */
@Service
public class CommandService implements IssueCommandUseCase, CommandResultUseCase {

    private static final Logger log = LoggerFactory.getLogger(CommandService.class);
    static final long ACK_TIMEOUT_MS = 5_000;
    static final int MAX_ATTEMPTS = 3;

    private final CommandRepository repository;
    private final GatewayCommandSender sender;
    private final LiveUpdates live;
    private final LongSupplier clock;
    /** 공장 ID → 그 공장을 맡은 게이트웨이 ID */
    private final Map<String, String> gatewayByFactory;
    private final ReentrantLock lock = new ReentrantLock();

    @Autowired
    public CommandService(CommandRepository repository, GatewayCommandSender sender, LiveUpdates live, FemProperties props) {
        this(repository, sender, live, props, System::currentTimeMillis);
    }

    CommandService(CommandRepository repository, GatewayCommandSender sender, LiveUpdates live,
                   FemProperties props, LongSupplier clock) {
        this.repository = repository;
        this.sender = sender;
        this.live = live;
        this.clock = clock;
        this.gatewayByFactory = props.gateways().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getValue().factory(), Map.Entry::getKey));
    }

    @Override
    public RemoteCommand issue(String commandId, String deviceId, CommandAction action, String requestedBy) {
        String gatewayId = gatewayFor(deviceId)
                .orElseThrow(() -> new IllegalArgumentException("unknown device: " + deviceId));
        lock.lock();
        try {
            Optional<RemoteCommand> existing = repository.find(commandId);
            if (existing.isPresent()) {
                RemoteCommand e = existing.get();
                if (!e.deviceId().equals(deviceId) || e.action() != action) {
                    throw new IllegalStateException("commandId " + commandId + " already used for " + e.action() + " " + e.deviceId());
                }
                return e; // 같은 요청의 재시도 (더블클릭, 네트워크 재전송) → 새로 만들지 않는다
            }
            long now = clock.getAsLong();
            RemoteCommand cmd = new RemoteCommand(commandId, deviceId, gatewayId, action, requestedBy, now);
            if (!repository.insertIfAbsent(cmd)) {
                return repository.find(commandId).orElseThrow(); // 다른 서버 스레드가 먼저 넣었다
            }
            audit(cmd, "REQUESTED", requestedBy, action + " " + deviceId);
            send(cmd);
            return cmd;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void onResult(String gatewayId, CommandResult result) {
        lock.lock();
        try {
            RemoteCommand cmd = repository.find(result.commandId()).orElse(null);
            if (cmd == null || !cmd.gatewayId().equals(gatewayId)) {
                log.warn("{} sent result for unknown command {}", gatewayId, result.commandId());
                return;
            }
            boolean changed = cmd.applyResult(result.outcome(), result.detail(), clock.getAsLong());
            if (!changed) {
                // 재시도 때문에 같은 명령의 결과가 두 번 올 수 있다. 처음 것만 반영하고 나머지는 기록만 한다
                audit(cmd, "DUPLICATE_RESULT_IGNORED", gatewayId, result.outcome().name());
                return;
            }
            repository.update(cmd);
            audit(cmd, "RESULT", gatewayId, result.outcome() + ": " + result.detail());
            live.commandChanged(cmd);
        } finally {
            lock.unlock();
        }
    }

    /** 1초마다: 응답이 늦은 명령은 재시도하거나 포기한다 */
    @Scheduled(fixedDelay = 1000)
    public void checkTimeouts() {
        lock.lock();
        try {
            long now = clock.getAsLong();
            for (RemoteCommand cmd : repository.findOpen()) {
                switch (cmd.checkTimeout(now, ACK_TIMEOUT_MS, MAX_ATTEMPTS)) {
                    case RETRY -> {
                        audit(cmd, "RETRY", "system", "attempt " + (cmd.attempts() + 1));
                        send(cmd);
                    }
                    case GIVE_UP -> {
                        cmd.markTimedOut(now);
                        repository.update(cmd);
                        audit(cmd, "TIMED_OUT", "system", cmd.detail());
                        live.commandChanged(cmd);
                    }
                    case NOT_DUE -> {
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<RemoteCommand> recent(int limit) {
        return repository.findRecent(limit);
    }

    @Override
    public List<AuditEntry> audit(String commandId) {
        return repository.auditOf(commandId);
    }

    /** 잠금 안에서만 부른다 */
    private void send(RemoteCommand cmd) {
        long now = clock.getAsLong();
        Command wire = new Command(cmd.commandId(), cmd.deviceId(), cmd.action(), cmd.createdAt(), cmd.attempts() + 1);
        if (sender.send(cmd.gatewayId(), wire)) {
            cmd.markSent(now);
            audit(cmd, "SENT", "system", "attempt " + cmd.attempts());
        } else {
            cmd.markSendFailed(now);
            audit(cmd, "SEND_FAILED", "system", "gateway " + cmd.gatewayId() + " not connected, attempt " + cmd.attempts());
        }
        repository.update(cmd);
        live.commandChanged(cmd);
    }

    private void audit(RemoteCommand cmd, String event, String actor, String detail) {
        repository.audit(new AuditEntry(cmd.commandId(), clock.getAsLong(), event, actor, detail));
    }

    private Optional<String> gatewayFor(String deviceId) {
        try {
            return Optional.ofNullable(gatewayByFactory.get(DeviceIds.factoryOf(deviceId)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
