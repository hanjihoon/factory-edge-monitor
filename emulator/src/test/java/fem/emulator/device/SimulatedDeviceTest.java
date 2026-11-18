package fem.emulator.device;

import fem.common.DeviceState;
import fem.common.wire.WireMessages.CommandAction;
import fem.common.wire.WireMessages.CommandOutcome;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class SimulatedDeviceTest {

    private SimulatedDevice device(double faultRate) {
        return new SimulatedDevice("A-0001", 1000, faultRate, new Random(42));
    }

    @Test
    void seq_increases_by_one_per_tick_starting_after_initial_value() {
        SimulatedDevice d = device(0);

        assertEquals(1001, d.tick(0).seq());
        assertEquals(1002, d.tick(1000).seq());
        assertEquals(1003, d.tick(2000).seq());
    }

    @Test
    void running_device_stays_near_nominal_values() {
        SimulatedDevice d = device(0);
        Reading r = null;
        for (int i = 0; i < 60; i++) r = d.tick(i * 1000L);

        assertEquals(DeviceState.RUNNING, r.state());
        assertEquals(1500, r.rpm(), 50);
        assertEquals(65, r.temperature(), 5);
        assertEquals(0, r.alarmCode());
    }

    @Test
    void stop_brings_rpm_to_zero_and_resume_restores_running() {
        SimulatedDevice d = device(0);
        d.tick(0);

        assertEquals(CommandOutcome.APPLIED, d.handle("c1", CommandAction.STOP));
        Reading stopped = null;
        for (int i = 0; i < 60; i++) stopped = d.tick(i * 1000L);
        assertEquals(DeviceState.STOPPED, stopped.state());
        assertEquals(0, stopped.rpm());

        assertEquals(CommandOutcome.APPLIED, d.handle("c2", CommandAction.RESUME));
        assertEquals(DeviceState.RUNNING, d.state());
    }

    @Test
    void same_command_id_is_applied_only_once() {
        SimulatedDevice d = device(0);

        assertEquals(CommandOutcome.APPLIED, d.handle("c1", CommandAction.STOP));
        d.handle("c2", CommandAction.RESUME);
        // c1 재시도가 늦게 도착해도 다시 정지시키면 안 된다. 처음 결과만 돌려준다.
        assertEquals(CommandOutcome.APPLIED, d.handle("c1", CommandAction.STOP));
        assertEquals(DeviceState.RUNNING, d.state());
    }

    @Test
    void stop_on_stopped_device_reports_already_in_state() {
        SimulatedDevice d = device(0);
        d.handle("c1", CommandAction.STOP);

        assertEquals(CommandOutcome.ALREADY_IN_STATE, d.handle("c2", CommandAction.STOP));
    }

    @Test
    void fault_raises_alarm_and_recovers_by_itself() {
        SimulatedDevice d = device(1.0); // 첫 tick에서 반드시 고장
        Reading fault = d.tick(0);
        assertEquals(DeviceState.FAULT, fault.state());
        assertNotEquals(0, fault.alarmCode());

        // 고장은 최대 30초. 넉넉히 31 tick 안에 RUNNING으로 돌아와야 한다.
        // (고장률 1.0이라 복구 직후 다시 고장날 수 있으므로 "한 번이라도 RUNNING"을 확인)
        boolean recovered = false;
        for (int i = 1; i <= 31 && !recovered; i++) {
            recovered = d.tick(i * 1000L).state() == DeviceState.RUNNING || d.state() == DeviceState.RUNNING;
        }
        assertTrue(recovered);
    }

    @Test
    void resume_clears_fault_and_alarm() {
        SimulatedDevice d = device(1.0);
        d.tick(0);

        assertEquals(CommandOutcome.APPLIED, d.handle("c1", CommandAction.RESUME));
        Reading r = d.current(1);
        assertEquals(DeviceState.RUNNING, r.state());
        assertEquals(0, r.alarmCode());
    }
}
