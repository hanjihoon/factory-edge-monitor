package fem.gateway.command;

import fem.common.wire.WireMessages.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CommandServiceTest {

    private final List<CommandResult> sent = new CopyOnWriteArrayList<>();
    private CommandService service;

    @AfterEach
    void close() {
        if (service != null) service.close();
    }

    /** 미리 정한 결과를 차례로 돌려주는 가짜 실행기 */
    private static final class ScriptedExecutor implements CommandExecutor {
        final AtomicInteger calls = new AtomicInteger();
        final Deque<CommandOutcome> outcomes = new ArrayDeque<>();
        CountDownLatch gate = new CountDownLatch(0);

        ScriptedExecutor(CommandOutcome... outcomes) {
            this.outcomes.addAll(List.of(outcomes));
        }

        @Override
        public Result execute(String commandId, String deviceId, CommandAction action) throws InterruptedException {
            calls.incrementAndGet();
            gate.await();
            return new Result(outcomes.isEmpty() ? CommandOutcome.APPLIED : outcomes.poll(), "");
        }
    }

    private static Command cmd(String id, int attempt) {
        return new Command(id, "A-0001", CommandAction.STOP, 0, attempt);
    }

    private void awaitSent(int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (sent.size() < n && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertEquals(n, sent.size());
    }

    @Test
    void executes_once_and_reports_result() throws Exception {
        ScriptedExecutor ex = new ScriptedExecutor(CommandOutcome.APPLIED);
        service = new CommandService(ex, sent::add, () -> 9L);

        service.handle(cmd("c-1", 1));

        awaitSent(1);
        assertEquals(new CommandResult("c-1", "A-0001", CommandOutcome.APPLIED, "", 9L), sent.getFirst());
    }

    @Test
    void retry_with_same_id_resends_first_result_without_executing_again() throws Exception {
        ScriptedExecutor ex = new ScriptedExecutor(CommandOutcome.APPLIED, CommandOutcome.ALREADY_IN_STATE);
        service = new CommandService(ex, sent::add, () -> 0L);

        service.handle(cmd("c-1", 1));
        awaitSent(1);
        service.handle(cmd("c-1", 2));
        awaitSent(2);

        assertEquals(1, ex.calls.get());
        assertEquals(CommandOutcome.APPLIED, sent.get(1).outcome());
    }

    @Test
    void duplicate_arriving_while_running_does_not_execute_twice() throws Exception {
        ScriptedExecutor ex = new ScriptedExecutor(CommandOutcome.APPLIED);
        ex.gate = new CountDownLatch(1); // 첫 실행을 붙잡아 둔다
        service = new CommandService(ex, sent::add, () -> 0L);

        service.handle(cmd("c-1", 1));
        service.handle(cmd("c-1", 2));
        ex.gate.countDown();

        awaitSent(2); // 두 요청 모두 결과를 받는다
        assertEquals(1, ex.calls.get());
    }

    @Test
    void unreachable_result_is_not_remembered_so_retry_executes_again() throws Exception {
        ScriptedExecutor ex = new ScriptedExecutor(CommandOutcome.DEVICE_UNREACHABLE, CommandOutcome.APPLIED);
        service = new CommandService(ex, sent::add, () -> 0L);

        service.handle(cmd("c-1", 1));
        awaitSent(1);
        service.handle(cmd("c-1", 2));
        awaitSent(2);

        assertEquals(2, ex.calls.get());
        assertEquals(List.of(CommandOutcome.DEVICE_UNREACHABLE, CommandOutcome.APPLIED),
                sent.stream().map(CommandResult::outcome).toList());
    }

    @Test
    void handle_returns_without_waiting_for_device() throws Exception {
        ScriptedExecutor ex = new ScriptedExecutor(CommandOutcome.APPLIED);
        ex.gate = new CountDownLatch(1);
        service = new CommandService(ex, sent::add, () -> 0L);

        long start = System.nanoTime();
        service.handle(cmd("c-1", 1));

        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 500);
        ex.gate.countDown();
        awaitSent(1);
    }
}
