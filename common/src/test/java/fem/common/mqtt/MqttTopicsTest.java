package fem.common.mqtt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MqttTopicsTest {

    @Test
    void builds_topics_from_design_document() {
        assertEquals("factory/A/device/A-0001/telemetry", MqttTopics.telemetry("A", "A-0001"));
        assertEquals("factory/A/device/A-0001/command", MqttTopics.command("A", "A-0001"));
        assertEquals("factory/A/device/A-0001/command/ack", MqttTopics.commandAck("A", "A-0001"));
        assertEquals("factory/A/device/+/telemetry", MqttTopics.allTelemetry("A"));
    }

    @Test
    void extracts_device_id_from_any_device_topic() {
        assertEquals("A-0600", MqttTopics.deviceIdOf("factory/A/device/A-0600/telemetry"));
        assertEquals("A-0002", MqttTopics.deviceIdOf("factory/A/device/A-0002/command/ack"));
    }

    @Test
    void rejects_foreign_topic() {
        assertThrows(IllegalArgumentException.class, () -> MqttTopics.deviceIdOf("other/A/device/x/telemetry"));
        assertThrows(IllegalArgumentException.class, () -> MqttTopics.deviceIdOf("factory/A"));
    }
}
