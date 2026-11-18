package fem.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeviceIdsTest {

    @Test
    void formats_with_four_digits() {
        assertEquals("A-0001", DeviceIds.of("A", 1));
        assertEquals("B-0400", DeviceIds.of("B", 400));
    }

    @Test
    void parses_number_and_factory() {
        assertEquals(37, DeviceIds.numberOf("A-0037"));
        assertEquals("B", DeviceIds.factoryOf("B-0037"));
    }

    @Test
    void rejects_invalid_values() {
        assertThrows(IllegalArgumentException.class, () -> DeviceIds.of("A", 0));
        assertThrows(IllegalArgumentException.class, () -> DeviceIds.numberOf("A0001"));
        assertThrows(IllegalArgumentException.class, () -> DeviceIds.factoryOf("-0001"));
    }
}
