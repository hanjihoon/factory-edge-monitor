package fem.gateway.command;

import fem.common.wire.WireMessages.CommandAction;
import fem.common.wire.WireMessages.CommandOutcome;
import fem.emulator.EmulatorConfig;
import fem.emulator.modbus.ModbusFleet;
import fem.gateway.collect.ModbusCollector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 에뮬레이터 Modbus 설비 100대(포트 1개)에 실제로 명령을 써 본다. */
class ModbusCommandExecutorTest {

    private ModbusFleet fleet;
    private List<ModbusCollector.LineSpec> lines;
    private ModbusCommandExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        fleet = new ModbusFleet(new EmulatorConfig("", 0, 100, port, 0, 0, 0, 0), 1);
        fleet.start();
        lines = ModbusCollector.layout("127.0.0.1", port, 100);
        executor = new ModbusCommandExecutor("B", lines);
    }

    @AfterEach
    void tearDown() {
        lines.forEach(l -> l.line().close());
        fleet.close();
    }

    @Test
    void stop_is_applied_then_repeated_stop_is_already_in_state() throws Exception {
        assertEquals(CommandOutcome.APPLIED, executor.execute("c-1", "B-0042", CommandAction.STOP).outcome());
        assertEquals(CommandOutcome.ALREADY_IN_STATE, executor.execute("c-2", "B-0042", CommandAction.STOP).outcome());
        assertEquals(CommandOutcome.APPLIED, executor.execute("c-3", "B-0042", CommandAction.RESUME).outcome());
    }

    @Test
    void device_outside_configured_lines_is_unknown() throws Exception {
        assertEquals(CommandOutcome.UNKNOWN_DEVICE, executor.execute("c", "B-0101", CommandAction.STOP).outcome());
        assertEquals(CommandOutcome.UNKNOWN_DEVICE, executor.execute("c", "A-0001", CommandAction.STOP).outcome());
        assertEquals(CommandOutcome.UNKNOWN_DEVICE, executor.execute("c", "B-xx", CommandAction.STOP).outcome());
    }

    @Test
    void unreachable_line_reports_device_unreachable() throws Exception {
        fleet.close(); // 라인 컨트롤러가 꺼진 상황
        lines.forEach(l -> l.line().close());

        var r = executor.execute("c-1", "B-0001", CommandAction.STOP);

        assertEquals(CommandOutcome.DEVICE_UNREACHABLE, r.outcome());
    }
}
