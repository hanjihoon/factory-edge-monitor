package fem.gateway.wire;

import fem.common.DeviceState;
import fem.common.Protocol;
import fem.common.TelemetryRecord;
import fem.common.wire.WireMessages.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WireCodecTest {

    private final WireCodec codec = new WireCodec();

    private static TelemetryRecord record(long seq) {
        return new TelemetryRecord("A-0001", "A", Protocol.MQTT, seq, 1000, 1010, DeviceState.RUNNING, 61.5, 2.31, 1480, 0);
    }

    @Test
    void batch_round_trips_with_records() {
        Batch batch = new Batch("gw-a", 1, 2, List.of(record(1), record(2)));

        Object decoded = codec.decode(codec.encode(batch));

        assertEquals(batch, decoded);
    }

    @Test
    void every_message_type_round_trips() {
        List<Object> messages = List.of(
                new Ack(40, 42),
                new GatewayStatus("gw-a", 10, 500, 600, 3, 99),
                new Command("c-1", "A-0001", CommandAction.STOP, 1, 2),
                new CommandResult("c-1", "A-0001", CommandOutcome.APPLIED, "ok", 3));

        for (Object m : messages) {
            assertEquals(m, codec.decode(codec.encode(m)));
        }
    }

    @Test
    void frame_has_type_and_body() {
        String text = codec.encode(new Ack(5, 7));

        assertTrue(text.contains("\"type\":\"ACK\""), text);
        assertTrue(text.contains("\"body\":{\"fromOutboxId\":5,\"toOutboxId\":7}"), text);
    }

    @Test
    void rejects_malformed_or_unknown_frames() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode("not json"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"type\":\"NOPE\",\"body\":{}}"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("{\"type\":\"ACK\"}"));
    }
}
