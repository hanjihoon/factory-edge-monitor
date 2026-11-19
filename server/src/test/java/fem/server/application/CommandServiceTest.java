package fem.server.application;

import fem.common.wire.WireMessages.Command;
import fem.common.wire.WireMessages.CommandAction;
import fem.common.wire.WireMessages.CommandOutcome;
import fem.common.wire.WireMessages.CommandResult;
import fem.server.application.port.out.LiveUpdates;
import fem.server.config.FemProperties;
import fem.server.domain.DeviceSnapshot;
import fem.server.domain.GatewayView;
import fem.server.domain.RemoteCommand;
import fem.server.domain.RemoteCommand.Status;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class CommandServiceTest {

    private final InMemoryCommandRepository repo = new InMemoryCommandRepository();
    private final List<Command> sent = new ArrayList<>();
    private final List<RemoteCommand> pushed = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong(1_000);
    private boolean gatewayConnected = true;

    private final CommandService service = new CommandService(repo,
            (gatewayId, cmd) -> {
                if (!gatewayConnected) return false;
                sent.add(cmd);
                return true;
            },
            new LiveUpdates() {
                @Override
                public void devicesChanged(List<DeviceSnapshot> devices) {
                }

                @Override
                public void gatewayChanged(GatewayView gateway) {
                }

                @Override
                public void commandChanged(RemoteCommand command) {
                    pushed.add(command);
                }
            },
            new FemProperties(Map.of(
                    "gw-a", new FemProperties.GatewayCredential("t", "A"),
                    "gw-b", new FemProperties.GatewayCredential("t", "B")), Map.of()),
            clock::get);

    private CommandResult result(String id, CommandOutcome outcome) {
        return new CommandResult(id, "A-0001", outcome, "detail", clock.get());
    }

    @Test
    void issue_routes_to_factory_gateway_and_sends_once() {
        RemoteCommand c = service.issue("c-1", "B-0042", CommandAction.STOP, "operator");

        assertEquals("gw-b", c.gatewayId());
        assertEquals(Status.SENT, c.status());
        assertEquals(List.of(new Command("c-1", "B-0042", CommandAction.STOP, 1_000, 1)), sent);
        assertEquals(List.of("REQUESTED", "SENT"), repo.eventsOf("c-1"));
        assertEquals("operator", repo.auditOf("c-1").getFirst().actor());
    }

    @Test
    void same_command_id_again_returns_existing_without_resending() {
        service.issue("c-1", "A-0001", CommandAction.STOP, "operator");

        RemoteCommand again = service.issue("c-1", "A-0001", CommandAction.STOP, "operator");

        assertEquals("c-1", again.commandId());
        assertEquals(1, sent.size());
    }

    @Test
    void same_command_id_for_different_request_is_rejected() {
        service.issue("c-1", "A-0001", CommandAction.STOP, "operator");

        assertThrows(IllegalStateException.class, () -> service.issue("c-1", "A-0002", CommandAction.STOP, "operator"));
    }

    @Test
    void unknown_factory_is_rejected() {
        assertThrows(IllegalArgumentException.class, () -> service.issue("c-1", "Z-0001", CommandAction.STOP, "op"));
        assertThrows(IllegalArgumentException.class, () -> service.issue("c-2", "garbage", CommandAction.STOP, "op"));
    }

    @Test
    void first_result_wins_and_duplicates_are_only_audited() {
        service.issue("c-1", "A-0001", CommandAction.STOP, "operator");

        service.onResult("gw-a", result("c-1", CommandOutcome.APPLIED));
        service.onResult("gw-a", result("c-1", CommandOutcome.ALREADY_IN_STATE));

        RemoteCommand c = repo.find("c-1").orElseThrow();
        assertEquals(Status.SUCCEEDED, c.status());
        assertEquals(CommandOutcome.APPLIED, c.outcome());
        assertEquals(List.of("REQUESTED", "SENT", "RESULT", "DUPLICATE_RESULT_IGNORED"), repo.eventsOf("c-1"));
    }

    @Test
    void result_from_wrong_gateway_is_ignored() {
        service.issue("c-1", "A-0001", CommandAction.STOP, "operator");

        service.onResult("gw-b", result("c-1", CommandOutcome.APPLIED));

        assertEquals(Status.SENT, repo.find("c-1").orElseThrow().status());
    }

    @Test
    void no_result_retries_with_same_id_then_times_out() {
        service.issue("c-1", "A-0001", CommandAction.STOP, "operator");

        clock.addAndGet(CommandService.ACK_TIMEOUT_MS);
        service.checkTimeouts();
        clock.addAndGet(CommandService.ACK_TIMEOUT_MS);
        service.checkTimeouts();
        clock.addAndGet(CommandService.ACK_TIMEOUT_MS);
        service.checkTimeouts();

        assertEquals(List.of(1, 2, 3), sent.stream().map(Command::attempt).toList());
        assertTrue(sent.stream().allMatch(c -> c.commandId().equals("c-1")), "retries reuse the command id");
        assertEquals(Status.TIMED_OUT, repo.find("c-1").orElseThrow().status());
        assertEquals(List.of("REQUESTED", "SENT", "RETRY", "SENT", "RETRY", "SENT", "TIMED_OUT"), repo.eventsOf("c-1"));
    }

    @Test
    void result_arriving_after_timeout_is_recorded_as_late_and_corrects_status() {
        service.issue("c-1", "A-0001", CommandAction.STOP, "operator");
        for (int i = 0; i < 3; i++) {
            clock.addAndGet(CommandService.ACK_TIMEOUT_MS);
            service.checkTimeouts();
        }

        service.onResult("gw-a", result("c-1", CommandOutcome.APPLIED));

        assertEquals(Status.SUCCEEDED, repo.find("c-1").orElseThrow().status());
        assertEquals("LATE_RESULT", repo.eventsOf("c-1").getLast());
    }

    @Test
    void late_result_after_retry_still_completes_once() {
        service.issue("c-1", "A-0001", CommandAction.STOP, "operator");
        clock.addAndGet(CommandService.ACK_TIMEOUT_MS);
        service.checkTimeouts(); // 2번째 시도

        service.onResult("gw-a", result("c-1", CommandOutcome.APPLIED));      // 1번째 시도의 늦은 응답
        service.onResult("gw-a", result("c-1", CommandOutcome.APPLIED));      // 2번째 시도의 응답 (게이트웨이가 재전송)

        assertEquals(Status.SUCCEEDED, repo.find("c-1").orElseThrow().status());
        assertEquals(1, repo.eventsOf("c-1").stream().filter("RESULT"::equals).count());
    }

    @Test
    void offline_gateway_is_retried_when_due() {
        gatewayConnected = false;
        RemoteCommand c = service.issue("c-1", "A-0001", CommandAction.RESUME, "operator");
        assertEquals(Status.PENDING, c.status());

        gatewayConnected = true;
        clock.addAndGet(CommandService.ACK_TIMEOUT_MS);
        service.checkTimeouts();

        assertEquals(1, sent.size());
        assertEquals(2, sent.getFirst().attempt());
        assertEquals(List.of("REQUESTED", "SEND_FAILED", "RETRY", "SENT"), repo.eventsOf("c-1"));
    }

    @Test
    void unreachable_device_is_retried_immediately_on_next_check() {
        service.issue("c-1", "A-0001", CommandAction.STOP, "operator");

        service.onResult("gw-a", result("c-1", CommandOutcome.DEVICE_UNREACHABLE));
        service.checkTimeouts(); // 시간이 지나지 않았어도 바로 재시도

        assertEquals(2, sent.size());
    }

    @Test
    void every_state_change_is_pushed_to_screen() {
        service.issue("c-1", "A-0001", CommandAction.STOP, "operator");
        service.onResult("gw-a", result("c-1", CommandOutcome.APPLIED));

        assertEquals(List.of(Status.SUCCEEDED), pushed.stream().skip(1).map(RemoteCommand::status).toList());
    }
}
