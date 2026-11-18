package fem.gateway.normalize;

import fem.common.DeviceState;
import fem.common.Protocol;
import fem.common.TelemetryRecord;
import fem.gateway.wire.WireCodec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class MqttNormalizerTest {

    private final MqttNormalizer normalizer = new MqttNormalizer("A", new WireCodec());

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void maps_payload_fields_to_common_model() {
        TelemetryRecord r = normalizer.normalize("factory/A/device/A-0007/telemetry",
                bytes("{\"seq\":42,\"ts\":1000,\"state\":\"FAULT\",\"temp\":91.2,\"vib\":7.5,\"rpm\":310,\"alarm\":101}"), 1050);

        assertEquals(new TelemetryRecord("A-0007", "A", Protocol.MQTT, 42, 1000, 1050, DeviceState.FAULT, 91.2, 7.5, 310, 101), r);
    }

    @Test
    void rejects_device_of_another_factory() {
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize("factory/A/device/B-0001/telemetry",
                bytes("{\"seq\":1,\"ts\":1,\"state\":\"RUNNING\",\"temp\":1,\"vib\":1,\"rpm\":1,\"alarm\":0}"), 0));
    }

    @Test
    void rejects_malformed_payloads() {
        assertThrows(IllegalArgumentException.class,
                () -> normalizer.normalize("factory/A/device/A-0001/telemetry", bytes("{oops"), 0));
        assertThrows(IllegalArgumentException.class,
                () -> normalizer.normalize("factory/A/device/A-0001/telemetry", bytes("{\"ts\":1}"), 0));
        assertThrows(IllegalArgumentException.class,
                () -> normalizer.normalize("factory/A/device/A-0001/telemetry",
                        bytes("{\"seq\":1,\"ts\":1,\"state\":\"BROKEN\",\"temp\":1,\"vib\":1,\"rpm\":1,\"alarm\":0}"), 0));
    }
}
