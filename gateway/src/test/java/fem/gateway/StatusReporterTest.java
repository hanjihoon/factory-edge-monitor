package fem.gateway;

import fem.common.DeviceState;
import fem.common.Protocol;
import fem.common.TelemetryRecord;
import fem.common.wire.WireMessages.GatewayStatus;
import fem.gateway.collect.DeviceActivity;
import fem.gateway.outbox.SqliteOutbox;
import fem.gateway.uplink.Uplink;
import fem.gateway.wire.WireCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class StatusReporterTest {

    @TempDir
    Path dir;

    @Test
    void snapshot_reports_backlog_age_active_devices_and_drops() throws Exception {
        AtomicLong clock = new AtomicLong(10_000);
        WireCodec codec = new WireCodec();
        try (SqliteOutbox outbox = new SqliteOutbox(dir.resolve("b.db"), 1, codec, clock::get)) {
            DeviceActivity activity = new DeviceActivity();
            Uplink uplink = new Uplink("gw-a", outbox, codec, c -> {}, clock::get, 10, 2, 1000);
            StatusReporter reporter = new StatusReporter("gw-a", outbox, activity, uplink, clock::get);

            TelemetryRecord r = new TelemetryRecord("A-0001", "A", Protocol.MQTT, 1, 0, 0, DeviceState.RUNNING, 0, 0, 0, 0);
            outbox.append(List.of(r, r)); // 상한 1 → 1건 버림
            activity.seen("A-0001", 9_000);
            activity.seen("A-0002", 1_000); // 5초보다 오래됨
            clock.set(13_000);

            assertEquals(new GatewayStatus("gw-a", 1, 3_000, 1, 1, 13_000), reporter.snapshot());
        }
    }

    @Test
    void empty_buffer_has_zero_age() throws Exception {
        WireCodec codec = new WireCodec();
        try (SqliteOutbox outbox = new SqliteOutbox(dir.resolve("c.db"), 10, codec, () -> 0)) {
            Uplink uplink = new Uplink("gw-a", outbox, codec, c -> {}, () -> 0, 10, 2, 1000);
            StatusReporter reporter = new StatusReporter("gw-a", outbox, new DeviceActivity(), uplink, () -> 99_999);

            assertEquals(0, reporter.snapshot().oldestBufferedAgeMs());
        }
    }
}
