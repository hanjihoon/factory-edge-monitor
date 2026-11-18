package fem.emulator.mqtt;

import fem.common.DeviceState;
import fem.common.mqtt.MqttPayloads;
import fem.emulator.device.Reading;
import fem.emulator.device.SimulatedDevice;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MqttCommandHandlerTest {

    private final SimulatedDevice device = new SimulatedDevice("A-0001", 0, 0, new Random(1));
    private final MqttCommandHandler handler = new MqttCommandHandler("A", Map.of("A-0001", device)::get, () -> 777L);

    private static byte[] json(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void applies_stop_and_answers_on_ack_topic() {
        var out = handler.handle("factory/A/device/A-0001/command", json("{\"commandId\":\"c-1\",\"action\":\"STOP\"}")).orElseThrow();

        assertEquals("factory/A/device/A-0001/command/ack", out.topic());
        var ack = MqttCodec.read(out.payload(), MqttPayloads.CommandAck.class);
        assertEquals(new MqttPayloads.CommandAck("c-1", "APPLIED", "STOPPED", 777L), ack);
        assertEquals(DeviceState.STOPPED, device.state());
    }

    @Test
    void retried_command_returns_first_outcome() {
        byte[] cmd = json("{\"commandId\":\"c-1\",\"action\":\"STOP\"}");
        handler.handle("factory/A/device/A-0001/command", cmd);
        var second = handler.handle("factory/A/device/A-0001/command", cmd).orElseThrow();

        assertEquals("APPLIED", MqttCodec.read(second.payload(), MqttPayloads.CommandAck.class).outcome());
    }

    @Test
    void unknown_device_gets_unknown_device_outcome() {
        var out = handler.handle("factory/A/device/A-9999/command", json("{\"commandId\":\"c-2\",\"action\":\"RESUME\"}")).orElseThrow();

        assertEquals("UNKNOWN_DEVICE", MqttCodec.read(out.payload(), MqttPayloads.CommandAck.class).outcome());
    }

    @Test
    void malformed_command_is_ignored() {
        assertTrue(handler.handle("factory/A/device/A-0001/command", json("not json")).isEmpty());
        assertTrue(handler.handle("factory/A/device/A-0001/command", json("{\"commandId\":\"c\",\"action\":\"EXPLODE\"}")).isEmpty());
        assertEquals(DeviceState.RUNNING, device.state());
    }

    @Test
    void telemetry_json_uses_design_document_keys() {
        Reading r = new Reading("A-0001", 42, 1000, DeviceState.RUNNING, 61.5, 2.31, 1480, 0);

        String s = new String(MqttCodec.telemetry(r), StandardCharsets.UTF_8);

        for (String key : new String[]{"\"seq\":42", "\"ts\":1000", "\"state\":\"RUNNING\"", "\"temp\":61.5", "\"vib\":2.31", "\"rpm\":1480", "\"alarm\":0"}) {
            assertTrue(s.contains(key), s + " should contain " + key);
        }
    }
}
