package fem.gateway.collect;

import fem.common.Protocol;
import fem.common.TelemetryRecord;
import fem.emulator.EmulatorConfig;
import fem.emulator.modbus.ModbusFleet;
import fem.gateway.normalize.ModbusNormalizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** 에뮬레이터의 실제 Modbus 설비 100대를 localhost에서 폴링한다. */
class ModbusCollectorTest {

    private ModbusFleet fleet;
    private ModbusCollector collector;
    private int port;
    private final List<TelemetryRecord> submitted = new CopyOnWriteArrayList<>();
    private final DeviceActivity activity = new DeviceActivity();

    @BeforeEach
    void setUp() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        fleet = new ModbusFleet(new EmulatorConfig("", 0, 100, port, 0, 0, 0, 0), 1_000);
        fleet.start();
        collector = new ModbusCollector(ModbusCollector.layout("127.0.0.1", port, 100),
                new ModbusNormalizer("B", port), (r, ack) -> submitted.add(r), activity, System::currentTimeMillis, 500);
    }

    @AfterEach
    void tearDown() {
        collector.close();
        fleet.close();
    }

    @Test
    void layout_splits_devices_into_lines_of_100() {
        var lines = ModbusCollector.layout("h", 5020, 250);

        assertEquals(List.of(5020, 5021, 5022), lines.stream().map(l -> l.line().port()).toList());
        assertEquals(List.of(100, 100, 50), lines.stream().map(ModbusCollector.LineSpec::units).toList());
    }

    @Test
    void one_cycle_reads_every_unit_once() throws Exception {
        var spec = ModbusCollector.layout("127.0.0.1", port, 100).getFirst();
        try {
            assertEquals(100, collector.pollOnce(spec));
        } finally {
            spec.line().close();
        }
        assertEquals(100, submitted.stream().map(TelemetryRecord::deviceId).distinct().count());
        assertTrue(submitted.stream().allMatch(r -> r.protocol() == Protocol.MODBUS && r.factoryId().equals("B")));
        assertEquals(100, activity.activeWithin(5_000, System.currentTimeMillis()));
    }

    @Test
    void repeated_polls_emit_each_counter_only_once() throws Exception {
        var spec = ModbusCollector.layout("127.0.0.1", port, 100).getFirst();
        try {
            for (int i = 0; i < 6; i++) { // 0.5초 간격 6번 = 약 3초
                collector.pollOnce(spec);
                Thread.sleep(500);
            }
        } finally {
            spec.line().close();
        }
        long distinct = submitted.stream().map(r -> r.deviceId() + "#" + r.seq()).distinct().count();
        assertEquals(submitted.size(), distinct, "no duplicate (deviceId, seq)");
        assertTrue(submitted.size() > 100, "counters advanced at least once");
    }

    @Test
    void unreachable_line_skips_cycle_quickly() throws Exception {
        int deadPort;
        try (ServerSocket s = new ServerSocket(0)) {
            deadPort = s.getLocalPort();
        }
        var spec = ModbusCollector.layout("127.0.0.1", deadPort, 100).getFirst();
        long start = System.currentTimeMillis();

        assertEquals(0, collector.pollOnce(spec));

        assertTrue(System.currentTimeMillis() - start < 3_000, "does not try all 100 units");
    }
}
