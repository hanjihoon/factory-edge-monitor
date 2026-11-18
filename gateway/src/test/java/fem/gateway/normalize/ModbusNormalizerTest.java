package fem.gateway.normalize;

import fem.common.DeviceState;
import fem.common.Protocol;
import fem.common.TelemetryRecord;
import fem.common.modbus.ModbusRegisterMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModbusNormalizerTest {

    private final ModbusNormalizer normalizer = new ModbusNormalizer("B", 5020);

    private static int[] regs(long seq) {
        return ModbusRegisterMap.encode(new ModbusRegisterMap.Sample(seq, DeviceState.RUNNING, 60.1, 2.05, 1500, 0));
    }

    @Test
    void maps_registers_and_port_unit_to_common_model() {
        TelemetryRecord r = normalizer.normalize(5022, 15, regs(9), 777).orElseThrow();

        assertEquals(new TelemetryRecord("B-0215", "B", Protocol.MODBUS, 9, 777, 777, DeviceState.RUNNING, 60.1, 2.05, 1500, 0), r);
    }

    @Test
    void same_counter_read_twice_is_emitted_once() {
        assertTrue(normalizer.normalize(5020, 1, regs(10), 0).isPresent());
        assertTrue(normalizer.normalize(5020, 1, regs(10), 500).isEmpty());
        assertTrue(normalizer.normalize(5020, 1, regs(11), 1000).isPresent());
    }

    @Test
    void older_counter_is_dropped_but_other_devices_are_independent() {
        normalizer.normalize(5020, 1, regs(20), 0);

        assertTrue(normalizer.normalize(5020, 1, regs(19), 0).isEmpty());
        assertTrue(normalizer.normalize(5020, 2, regs(19), 0).isPresent());
    }
}
