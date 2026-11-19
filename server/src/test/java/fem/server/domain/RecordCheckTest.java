package fem.server.domain;

import fem.common.DeviceState;
import fem.common.Protocol;
import fem.common.TelemetryRecord;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RecordCheckTest {

    private static final long NOW = 1_795_000_000_000L; // 2026-11

    private static TelemetryRecord rec(String deviceId, String factoryId, DeviceState state, long deviceTs) {
        return new TelemetryRecord(deviceId, factoryId, Protocol.MQTT, 1, deviceTs, NOW, state, 20, 1, 1500, 0);
    }

    private static TelemetryRecord rec(String deviceId, String factoryId, DeviceState state) {
        return rec(deviceId, factoryId, state, NOW - 1_000);
    }

    @Test
    void normal_record_passes() {
        assertEquals(Optional.empty(), RecordCheck.problem(rec("A-0001", "A", DeviceState.RUNNING), "A", NOW));
    }

    @Test
    void missing_or_unknown_fields_are_caught() {
        assertTrue(RecordCheck.problem(rec(null, "A", DeviceState.RUNNING), "A", NOW).isPresent());
        assertTrue(RecordCheck.problem(rec("A-0001", "A", null), "A", NOW).isPresent());
    }

    @Test
    void another_factorys_device_is_caught() {
        assertTrue(RecordCheck.problem(rec("B-0001", "B", DeviceState.RUNNING), "A", NOW).isPresent());
        assertTrue(RecordCheck.problem(rec("B-0001", "A", DeviceState.RUNNING), "A", NOW).isPresent(), "prefix must match too");
    }

    @Test
    void timestamps_the_database_cannot_hold_are_caught() {
        assertTrue(RecordCheck.problem(rec("A-0001", "A", DeviceState.RUNNING, 0), "A", NOW).isPresent());
        assertTrue(RecordCheck.problem(rec("A-0001", "A", DeviceState.RUNNING, Long.MAX_VALUE), "A", NOW).isPresent());
        assertEquals(Optional.empty(), RecordCheck.problem(rec("A-0001", "A", DeviceState.RUNNING, RecordCheck.MAX_EPOCH_MS), "A", NOW));
    }

    @Test
    void device_time_older_than_retention_is_caught_because_retention_would_delete_it_silently() {
        // 설비 시계가 초기화돼 1970년이나 작년 시각을 보내는 경우
        long justOutside = NOW - RecordCheck.RETENTION_MS - 1;
        long justInside = NOW - RecordCheck.RETENTION_MS + 60_000;

        assertTrue(RecordCheck.problem(rec("A-0001", "A", DeviceState.RUNNING, justOutside), "A", NOW).isPresent());
        assertTrue(RecordCheck.problem(rec("A-0001", "A", DeviceState.RUNNING, 1_000), "A", NOW).isPresent());
        assertEquals(Optional.empty(), RecordCheck.problem(rec("A-0001", "A", DeviceState.RUNNING, justInside), "A", NOW));
    }
}
