package fem.gateway.collect;

import fem.common.TelemetryRecord;
import fem.gateway.normalize.MqttNormalizer;
import fem.gateway.wire.WireCodec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Paho 없이 메시지 처리 규칙(언제 ack 하는가)만 검증한다. */
class MqttCollectorTest {

    private final List<TelemetryRecord> submitted = new ArrayList<>();
    private final List<Runnable> pendingAcks = new ArrayList<>();
    private final DeviceActivity activity = new DeviceActivity();
    private final MqttCollector collector = new MqttCollector("tcp://unused:1883", "gw-a", "A",
            new MqttNormalizer("A", new WireCodec()),
            (record, onDurable) -> {
                submitted.add(record);
                pendingAcks.add(onDurable); // 디스크 커밋을 흉내 내려고 바로 부르지 않고 모아 둔다
            },
            activity, () -> 5_000L);

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void telemetry_is_acked_only_after_durable_write() throws Exception {
        AtomicInteger acks = new AtomicInteger();

        collector.onMessage("factory/A/device/A-0001/telemetry",
                bytes("{\"seq\":1,\"ts\":1,\"state\":\"RUNNING\",\"temp\":1,\"vib\":1,\"rpm\":1,\"alarm\":0}"), acks::incrementAndGet);

        assertEquals(1, submitted.size());
        assertEquals(0, acks.get(), "not acked before disk commit");
        pendingAcks.forEach(Runnable::run);
        assertEquals(1, acks.get());
        assertEquals(1, activity.activeWithin(5_000, 5_000));
    }

    @Test
    void malformed_telemetry_is_acked_and_dropped() throws Exception {
        AtomicInteger acks = new AtomicInteger();

        collector.onMessage("factory/A/device/A-0001/telemetry", bytes("broken"), acks::incrementAndGet);

        assertTrue(submitted.isEmpty());
        assertEquals(1, acks.get(), "acked so the broker stops redelivering a poison message");
    }

    @Test
    void command_ack_goes_to_command_handler_not_to_disk() throws Exception {
        List<String> topics = new ArrayList<>();
        collector.onCommandAck((topic, payload) -> topics.add(topic));
        AtomicInteger acks = new AtomicInteger();

        collector.onMessage("factory/A/device/A-0001/command/ack", bytes("{}"), acks::incrementAndGet);

        assertEquals(List.of("factory/A/device/A-0001/command/ack"), topics);
        assertTrue(submitted.isEmpty());
        assertEquals(1, acks.get());
    }
}
