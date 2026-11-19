package fem.gateway.command;

import fem.common.mqtt.MqttPayloads;
import fem.common.wire.WireMessages.CommandAction;
import fem.common.wire.WireMessages.CommandOutcome;
import fem.gateway.wire.WireCodec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MqttCommandExecutorTest {

    private final WireCodec codec = new WireCodec();
    private final List<String> publishedTopics = new ArrayList<>();
    private MqttCommandExecutor executor;

    private byte[] ack(String commandId, String outcome) {
        return codec.toJson(new MqttPayloads.CommandAck(commandId, outcome, "STOPPED", 1)).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void publishes_command_and_returns_device_outcome() throws Exception {
        // 설비가 즉시 응답하는 상황: publish 안에서 바로 ack를 돌려준다
        executor = new MqttCommandExecutor("A", (topic, payload) -> {
            publishedTopics.add(topic);
            var cmd = codec.fromJson(new String(payload, StandardCharsets.UTF_8), MqttPayloads.Command.class);
            assertEquals("STOP", cmd.action());
            executor.onAck("factory/A/device/A-0003/command/ack", ack(cmd.commandId(), "APPLIED"));
        }, codec, 1000);

        var result = executor.execute("c-1", "A-0003", CommandAction.STOP);

        assertEquals(CommandOutcome.APPLIED, result.outcome());
        assertEquals(List.of("factory/A/device/A-0003/command"), publishedTopics);
    }

    @Test
    void no_ack_within_timeout_is_unreachable() throws Exception {
        executor = new MqttCommandExecutor("A", (topic, payload) -> {}, codec, 100);

        assertEquals(CommandOutcome.DEVICE_UNREACHABLE, executor.execute("c-1", "A-0001", CommandAction.STOP).outcome());
    }

    @Test
    void ack_for_other_command_does_not_complete() throws Exception {
        executor = new MqttCommandExecutor("A", (topic, payload) ->
                executor.onAck(topic + "/ack", ack("someone-else", "APPLIED")), codec, 100);

        assertEquals(CommandOutcome.DEVICE_UNREACHABLE, executor.execute("c-1", "A-0001", CommandAction.STOP).outcome());
    }

    @Test
    void publish_failure_is_unreachable() throws Exception {
        executor = new MqttCommandExecutor("A", (topic, payload) -> {
            throw new IllegalStateException("broker down");
        }, codec, 100);

        var r = executor.execute("c-1", "A-0001", CommandAction.RESUME);

        assertEquals(CommandOutcome.DEVICE_UNREACHABLE, r.outcome());
        assertTrue(r.detail().contains("broker down"));
    }

    @Test
    void device_of_other_factory_is_unknown() throws Exception {
        executor = new MqttCommandExecutor("A", (topic, payload) -> fail("must not publish"), codec, 100);

        assertEquals(CommandOutcome.UNKNOWN_DEVICE, executor.execute("c-1", "B-0001", CommandAction.STOP).outcome());
    }
}
