package fem.common.modbus;

import fem.common.DeviceState;
import fem.common.modbus.ModbusRegisterMap.Sample;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModbusRegisterMapTest {

    @Test
    void encode_then_decode_returns_same_values() {
        Sample in = new Sample(123_456L, DeviceState.RUNNING, 61.5, 2.31, 1480, 0);

        Sample out = ModbusRegisterMap.decode(ModbusRegisterMap.encode(in));

        assertEquals(in, out);
    }

    @Test
    void seq_is_split_into_high_and_low_words() {
        int[] r = ModbusRegisterMap.encode(new Sample(0x0001_0002L, DeviceState.STOPPED, 0, 0, 0, 0));

        assertEquals(1, r[ModbusRegisterMap.COUNTER_HI]);
        assertEquals(2, r[ModbusRegisterMap.COUNTER_LO]);
        assertEquals(DeviceState.STOPPED.code(), r[ModbusRegisterMap.STATE]);
    }

    @Test
    void max_uint32_seq_survives_round_trip() {
        Sample in = new Sample(0xFFFF_FFFFL, DeviceState.FAULT, 0, 0, 0, 17);

        assertEquals(0xFFFF_FFFFL, ModbusRegisterMap.decode(ModbusRegisterMap.encode(in)).seq());
    }

    @Test
    void negative_temperature_is_stored_as_int16() {
        int[] r = ModbusRegisterMap.encode(new Sample(1, DeviceState.RUNNING, -12.3, 0, 0, 0));

        assertTrue(r[ModbusRegisterMap.TEMPERATURE] >= 0x8000, "two's complement in an unsigned register");
        assertEquals(-12.3, ModbusRegisterMap.decode(r).temperature(), 1e-9);
    }

    @Test
    void every_register_value_fits_in_16_bits() {
        int[] r = ModbusRegisterMap.encode(new Sample(99, DeviceState.RUNNING, 3276.7, 999.0, 100_000, 70_000));

        for (int v : r) {
            assertTrue(v >= 0 && v <= 0xFFFF, "value " + v);
        }
    }

    @Test
    void seq_beyond_uint32_is_rejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ModbusRegisterMap.encode(new Sample(1L << 32, DeviceState.RUNNING, 0, 0, 0, 0)));
    }

    @Test
    void port_and_unit_map_to_device_id_and_back() {
        String id = ModbusRegisterMap.deviceId("B", 5020, 5021, 7);

        assertEquals("B-0107", id);
        assertEquals(1, ModbusRegisterMap.portOffsetOf(id));
        assertEquals(7, ModbusRegisterMap.unitOf(id));
    }

    @Test
    void last_unit_of_a_port_stays_on_that_port() {
        // 경계값: B-0100은 포트 0의 unit 100, B-0101은 포트 1의 unit 1
        assertEquals("B-0100", ModbusRegisterMap.deviceId("B", 5020, 5020, 100));
        assertEquals(0, ModbusRegisterMap.portOffsetOf("B-0100"));
        assertEquals(100, ModbusRegisterMap.unitOf("B-0100"));
        assertEquals(1, ModbusRegisterMap.portOffsetOf("B-0101"));
        assertEquals(1, ModbusRegisterMap.unitOf("B-0101"));
    }

    @Test
    void decode_rejects_short_array() {
        assertThrows(IllegalArgumentException.class, () -> ModbusRegisterMap.decode(new int[3]));
    }
}
