package fem.emulator.modbus;

import fem.common.DeviceState;
import fem.common.modbus.ModbusRegisterMap;
import fem.emulator.device.Reading;
import com.ghgande.j2mod.modbus.procimg.Register;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeviceRegistersTest {

    private static int[] values(Register[] regs) {
        int[] v = new int[regs.length];
        for (int i = 0; i < regs.length; i++) v[i] = regs[i].getValue();
        return v;
    }

    @Test
    void update_writes_reading_in_register_map_layout() {
        DeviceRegisters regs = new DeviceRegisters(1, v -> {});
        regs.update(new Reading("B-0001", 70_000, 0, DeviceState.FAULT, 88.8, 7.5, 300, 101));

        var sample = ModbusRegisterMap.decode(values(regs.image().getRegisterRange(0, ModbusRegisterMap.TELEMETRY_COUNT)));

        assertEquals(new ModbusRegisterMap.Sample(70_000, DeviceState.FAULT, 88.8, 7.5, 300, 101), sample);
    }

    @Test
    void control_write_triggers_callback_and_resets_to_zero() throws Exception {
        List<Integer> seen = new ArrayList<>();
        DeviceRegisters regs = new DeviceRegisters(1, seen::add);
        Register control = regs.image().getRegister(ModbusRegisterMap.CONTROL);

        control.setValue(new byte[]{0, 2});   // FC06 경로
        control.setValue(ModbusRegisterMap.CONTROL_RESUME); // FC16 경로

        assertEquals(List.of(ModbusRegisterMap.CONTROL_STOP, ModbusRegisterMap.CONTROL_RESUME), seen);
        assertEquals(0, control.getValue());
    }

    @Test
    void writing_zero_to_control_does_nothing() throws Exception {
        List<Integer> seen = new ArrayList<>();
        DeviceRegisters regs = new DeviceRegisters(1, seen::add);

        regs.image().getRegister(ModbusRegisterMap.CONTROL).setValue(0);

        assertTrue(seen.isEmpty());
    }
}
