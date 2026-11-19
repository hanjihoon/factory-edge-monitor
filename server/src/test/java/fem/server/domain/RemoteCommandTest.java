package fem.server.domain;

import fem.common.wire.WireMessages.CommandAction;
import fem.common.wire.WireMessages.CommandOutcome;
import fem.server.domain.RemoteCommand.Status;
import fem.server.domain.RemoteCommand.TimeoutDecision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RemoteCommandTest {

    private static RemoteCommand cmd() {
        return new RemoteCommand("c-1", "A-0001", "gw-a", CommandAction.STOP, "operator", 0);
    }

    @Test
    void applied_result_succeeds_and_later_results_are_ignored() {
        RemoteCommand c = cmd();
        c.markSent(100);

        assertTrue(c.applyResult(CommandOutcome.APPLIED, "ok", 200));
        assertFalse(c.applyResult(CommandOutcome.ALREADY_IN_STATE, "late duplicate", 300));

        assertEquals(Status.SUCCEEDED, c.status());
        assertEquals(CommandOutcome.APPLIED, c.outcome());
        assertEquals(200, c.completedAt());
    }

    @Test
    void already_in_state_counts_as_success() {
        RemoteCommand c = cmd();
        c.markSent(0);
        c.applyResult(CommandOutcome.ALREADY_IN_STATE, "", 1);

        assertEquals(Status.SUCCEEDED, c.status());
    }

    @Test
    void unknown_device_fails_without_retry() {
        RemoteCommand c = cmd();
        c.markSent(0);
        c.applyResult(CommandOutcome.UNKNOWN_DEVICE, "", 1);

        assertEquals(Status.FAILED, c.status());
        assertEquals(TimeoutDecision.NOT_DUE, c.checkTimeout(1_000_000, 5000, 3));
    }

    @Test
    void timeout_retries_until_attempts_are_exhausted() {
        RemoteCommand c = cmd();
        c.markSent(0);

        assertEquals(TimeoutDecision.NOT_DUE, c.checkTimeout(4_999, 5000, 3));
        assertEquals(TimeoutDecision.RETRY, c.checkTimeout(5_000, 5000, 3));
        c.markSent(5_000);
        c.markSent(10_000);
        assertEquals(TimeoutDecision.GIVE_UP, c.checkTimeout(15_000, 5000, 3));

        c.markTimedOut(15_000);
        assertEquals(Status.TIMED_OUT, c.status());
        assertEquals(3, c.attempts());
    }

    @Test
    void unreachable_result_makes_retry_due_immediately() {
        RemoteCommand c = cmd();
        c.markSent(1_000);

        c.applyResult(CommandOutcome.DEVICE_UNREACHABLE, "no ack", 1_100);

        assertEquals(Status.PENDING, c.status());
        assertEquals(TimeoutDecision.RETRY, c.checkTimeout(1_100, 5000, 3));
    }

    @Test
    void finished_command_cannot_be_sent_again() {
        RemoteCommand c = cmd();
        c.markSent(0);
        c.applyResult(CommandOutcome.APPLIED, "", 1);

        assertThrows(IllegalStateException.class, () -> c.markSent(2));
    }
}
