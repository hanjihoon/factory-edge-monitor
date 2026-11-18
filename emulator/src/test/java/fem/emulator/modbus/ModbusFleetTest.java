package fem.emulator.modbus;

import fem.common.DeviceState;
import fem.common.modbus.ModbusRegisterMap;
import fem.emulator.EmulatorConfig;
import com.ghgande.j2mod.modbus.facade.ModbusTCPMaster;
import com.ghgande.j2mod.modbus.procimg.Register;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;

/** localhost에서 실제 Modbus TCP로 읽고 쓴다. 게이트웨이가 하는 일과 같은 경로다. */
class ModbusFleetTest {

    private static final long INITIAL_SEQ = 5_000;
    private ModbusFleet fleet;
    private ModbusTCPMaster master;
    private int port;

    @BeforeEach
    void start() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        // 100대 = 포트 1개
        EmulatorConfig cfg = new EmulatorConfig("", 0, 100, port, 0, 0, 0, 0);
        fleet = new ModbusFleet(cfg, INITIAL_SEQ);
        fleet.start();
        master = new ModbusTCPMaster("127.0.0.1", port);
        master.connect();
    }

    @AfterEach
    void stop() {
        if (master != null) master.disconnect();
        if (fleet != null) fleet.close();
    }

    private ModbusRegisterMap.Sample read(int unit) throws Exception {
        Register[] regs = master.readMultipleRegisters(unit, 0, ModbusRegisterMap.TELEMETRY_COUNT);
        int[] v = new int[regs.length];
        for (int i = 0; i < regs.length; i++) v[i] = regs[i].getValue();
        return ModbusRegisterMap.decode(v);
    }

    @Test
    void each_unit_exposes_its_own_counter_that_advances_every_second() throws Exception {
        ModbusRegisterMap.Sample first = read(100);
        assertEquals(DeviceState.RUNNING, first.state());
        assertTrue(first.seq() >= INITIAL_SEQ);

        // 시작 위상이 0~999ms라 2.5초면 최소 한 번은 증가한다
        Thread.sleep(2500);
        assertTrue(read(100).seq() > first.seq());
        assertTrue(read(1).seq() > INITIAL_SEQ);
    }

    @Test
    void writing_stop_to_control_register_changes_state_immediately() throws Exception {
        master.writeSingleRegister(7, ModbusRegisterMap.CONTROL, new SimpleRegister(ModbusRegisterMap.CONTROL_STOP));
        assertEquals(DeviceState.STOPPED, read(7).state());
        assertEquals(DeviceState.RUNNING, read(8).state(), "other units are untouched");

        master.writeSingleRegister(7, ModbusRegisterMap.CONTROL, new SimpleRegister(ModbusRegisterMap.CONTROL_RESUME));
        assertEquals(DeviceState.RUNNING, read(7).state());
    }
}
