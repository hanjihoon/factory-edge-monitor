package fem.gateway.command;

import fem.common.wire.WireMessages.Command;
import fem.common.wire.WireMessages.CommandOutcome;
import fem.common.wire.WireMessages.CommandResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 서버에서 온 명령을 실행하고 결과를 돌려보낸다. 게이트웨이 단의 멱등 처리를 맡는다.
 *
 * <h2>규칙</h2>
 * <ul>
 *   <li>같은 commandId가 다시 오면 실행하지 않고 처음 결과를 다시 보낸다 (실행 중이면 끝난 뒤 보낸다).</li>
 *   <li>단, {@link CommandOutcome#DEVICE_UNREACHABLE}은 기억하지 않는다. 서버가 재시도하면 다시 실행한다.
 *       MQTT에서는 설비가 적용했지만 응답만 잃었을 수도 있는데, 다시 보내도 설비가 commandId로 거르고
 *       Modbus는 상태 기준으로 멱등이라 두 번 적용되지 않는다.</li>
 *   <li>실행은 가상 스레드에서 한다. WebSocket 수신 스레드에서 설비 응답을 기다리면 ack 처리까지 막힌다.</li>
 * </ul>
 */
public final class CommandService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CommandService.class);
    private static final int REMEMBERED = 1000;

    private final CommandExecutor executor;
    private final Consumer<CommandResult> resultSink;
    private final LongSupplier clock;
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

    /** commandId → 실행 중이거나 끝난 결과. 오래된 것부터 지운다 */
    private final Map<String, CompletableFuture<CommandResult>> results = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CompletableFuture<CommandResult>> eldest) {
            return size() > REMEMBERED;
        }
    };

    public CommandService(CommandExecutor executor, Consumer<CommandResult> resultSink, LongSupplier clock) {
        this.executor = executor;
        this.resultSink = resultSink;
        this.clock = clock;
    }

    /** 바로 반환한다. 결과는 나중에 resultSink로 나간다. */
    public void handle(Command cmd) {
        CompletableFuture<CommandResult> future;
        boolean fresh = false;
        synchronized (results) {
            future = results.get(cmd.commandId());
            if (future == null) {
                future = new CompletableFuture<>();
                results.put(cmd.commandId(), future);
                fresh = true;
            }
        }
        if (fresh) {
            log.info("execute {} {} {} (attempt {})", cmd.commandId(), cmd.action(), cmd.deviceId(), cmd.attempt());
            CompletableFuture<CommandResult> f = future;
            threads.submit(() -> run(cmd, f));
        } else {
            log.info("command {} already seen, resend result", cmd.commandId());
        }
        future.thenAccept(resultSink);
    }

    private void run(Command cmd, CompletableFuture<CommandResult> future) {
        CommandExecutor.Result r;
        try {
            r = executor.execute(cmd.commandId(), cmd.deviceId(), cmd.action());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            r = new CommandExecutor.Result(CommandOutcome.DEVICE_UNREACHABLE, "interrupted");
        } catch (RuntimeException e) {
            r = new CommandExecutor.Result(CommandOutcome.DEVICE_UNREACHABLE, e.toString());
        }
        if (r.outcome() == CommandOutcome.DEVICE_UNREACHABLE) {
            synchronized (results) {
                results.remove(cmd.commandId()); // 다음 재시도 때 다시 실행하도록
            }
        }
        future.complete(new CommandResult(cmd.commandId(), cmd.deviceId(), r.outcome(), r.detail(), clock.getAsLong()));
    }

    @Override
    public void close() {
        threads.shutdownNow();
    }
}
