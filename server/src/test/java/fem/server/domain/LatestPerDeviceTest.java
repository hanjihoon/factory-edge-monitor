package fem.server.domain;

import fem.common.DeviceState;
import fem.common.Protocol;
import fem.common.TelemetryRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LatestPerDeviceTest {

    private static TelemetryRecord r(String device, long seq) {
        return new TelemetryRecord(device, "A", Protocol.MQTT, seq, seq, seq, DeviceState.RUNNING, 0, 0, 0, 0);
    }

    @Test
    void keeps_highest_seq_per_device_even_when_out_of_order() {
        List<TelemetryRecord> out = LatestPerDevice.of(List.of(
                r("A-0001", 5), r("A-0002", 1), r("A-0001", 7), r("A-0001", 6), r("A-0002", 1)));

        assertEquals(List.of(r("A-0001", 7), r("A-0002", 1)), out);
    }

    @Test
    void empty_input_gives_empty_output() {
        assertTrue(LatestPerDevice.of(List.of()).isEmpty());
    }
}
