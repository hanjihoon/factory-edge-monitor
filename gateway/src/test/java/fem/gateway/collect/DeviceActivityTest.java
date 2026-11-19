package fem.gateway.collect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeviceActivityTest {

    @Test
    void counts_devices_seen_within_window() {
        DeviceActivity a = new DeviceActivity();
        a.seen("A-0001", 1_000);
        a.seen("A-0002", 4_000);
        a.seen("A-0001", 6_000); // 갱신

        assertEquals(2, a.activeWithin(5_000, 6_000));
        assertEquals(1, a.activeWithin(1_000, 6_000));
    }
}
