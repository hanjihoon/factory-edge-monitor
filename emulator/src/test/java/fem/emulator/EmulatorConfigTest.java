package fem.emulator;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EmulatorConfigTest {

    @Test
    void defaults_match_assignment_scale() {
        EmulatorConfig c = EmulatorConfig.from(Map.of("MQTT_URL", "tcp://broker:1883"));

        assertEquals(600, c.mqttDevices());
        assertEquals(400, c.modbusDevices());
        assertEquals(5020, c.modbusBasePort());
        assertTrue(c.mqttEnabled());
        assertTrue(c.modbusEnabled());
    }

    @Test
    void mqtt_is_disabled_without_url() {
        assertFalse(EmulatorConfig.from(Map.of()).mqttEnabled());
    }

    @Test
    void reads_fault_rates_from_env() {
        EmulatorConfig c = EmulatorConfig.from(Map.of(
                "FAULT_DUPLICATE_RATE", "0.5", "FAULT_REORDER_RATE", "0.25", "FAULT_DELAY_MAX_MS", "0", "MODBUS_DEVICES", "0"));

        assertEquals(0.5, c.duplicateRate());
        assertEquals(0.25, c.reorderRate());
        assertEquals(0, c.delayMaxMs());
        assertFalse(c.modbusEnabled());
    }
}
