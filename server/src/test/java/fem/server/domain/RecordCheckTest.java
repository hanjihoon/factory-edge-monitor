package fem.server.domain;

import fem.common.DeviceState;
import fem.common.Protocol;
import fem.common.TelemetryRecord;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RecordCheckTest {

    private static TelemetryRecord rec(String deviceId, String factoryId, DeviceState state, long deviceTs) {
        return new TelemetryRecord(deviceId, factoryId, Protocol.MQTT, 1, deviceTs, 1_000, state, 20, 1, 1500, 0);
    }

    @Test
    void normal_record_passes() {
        assertEquals(Optional.empty(), RecordCheck.problem(rec("A-0001", "A", DeviceState.RUNNING, 1_000), "A"));
    }

    @Test
    void missing_or_unknown_fields_are_caught() {
        assertTrue(RecordCheck.problem(rec(null, "A", DeviceState.RUNNING, 1_000), "A").isPresent());
        assertTrue(RecordCheck.problem(rec("A-0001", "A", null, 1_000), "A").isPresent());
    }

    @Test
    void another_factorys_device_is_caught() {
        assertTrue(RecordCheck.problem(rec("B-0001", "B", DeviceState.RUNNING, 1_000), "A").isPresent());
        assertTrue(RecordCheck.problem(rec("B-0001", "A", DeviceState.RUNNING, 1_000), "A").isPresent(), "prefix must match too");
    }

    @Test
    void timestamps_the_database_cannot_hold_are_caught() {
        assertTrue(RecordCheck.problem(rec("A-0001", "A", DeviceState.RUNNING, 0), "A").isPresent());
        assertTrue(RecordCheck.problem(rec("A-0001", "A", DeviceState.RUNNING, Long.MAX_VALUE), "A").isPresent());
        assertEquals(Optional.empty(), RecordCheck.problem(rec("A-0001", "A", DeviceState.RUNNING, RecordCheck.MAX_EPOCH_MS), "A"));
    }
}
