package fem.gateway.command;

import fem.common.DeviceIds;
import fem.common.DeviceState;
import fem.common.modbus.ModbusRegisterMap;
import fem.common.wire.WireMessages.CommandAction;
import fem.common.wire.WireMessages.CommandOutcome;
import fem.gateway.collect.ModbusCollector.LineSpec;

import java.util.List;

/**
 * Modbus 설비 명령: 상태 확인 → 제어 레지스터(10번) 쓰기 → 상태 다시 읽어 확인.
 *
 * <p>Modbus에는 commandId를 실을 곳이 없다. 대신 "이미 목표 상태면 쓰지 않는다"로 상태 기준 멱등을 만든다.
 * 같은 명령이 두 번 와도 두 번째는 ALREADY_IN_STATE가 된다.
 */
public final class ModbusCommandExecutor implements CommandExecutor {

    private final String factoryId;
    private final List<LineSpec> lines;

    public ModbusCommandExecutor(String factoryId, List<LineSpec> lines) {
        this.factoryId = factoryId;
        this.lines = lines;
    }

    @Override
    public Result execute(String commandId, String deviceId, CommandAction action) throws InterruptedException {
        LineSpec spec = find(deviceId);
        if (spec == null) {
            return new Result(CommandOutcome.UNKNOWN_DEVICE, "no such modbus device: " + deviceId);
        }
        int unit = ModbusRegisterMap.unitOf(deviceId);
        DeviceState target = action == CommandAction.STOP ? DeviceState.STOPPED : DeviceState.RUNNING;
        int control = action == CommandAction.STOP ? ModbusRegisterMap.CONTROL_STOP : ModbusRegisterMap.CONTROL_RESUME;
        try {
            if (readState(spec, unit) == target) {
                return new Result(CommandOutcome.ALREADY_IN_STATE, "device state " + target);
            }
            spec.line().write(unit, ModbusRegisterMap.CONTROL, control);
            DeviceState after = readState(spec, unit);
            if (after == target) {
                return new Result(CommandOutcome.APPLIED, "device state " + after);
            }
            return new Result(CommandOutcome.DEVICE_UNREACHABLE, "state still " + after + " after write");
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            return new Result(CommandOutcome.DEVICE_UNREACHABLE, "modbus error: " + e.getMessage());
        }
    }

    /** deviceId가 이 공장의 실제 라인·unit 범위 안이면 그 라인, 아니면 null */
    private LineSpec find(String deviceId) {
        try {
            if (!factoryId.equals(DeviceIds.factoryOf(deviceId))) return null;
            int offset = ModbusRegisterMap.portOffsetOf(deviceId);
            int unit = ModbusRegisterMap.unitOf(deviceId);
            if (offset < 0 || offset >= lines.size() || unit > lines.get(offset).units()) return null;
            return lines.get(offset);
        } catch (IllegalArgumentException e) { // 숫자가 아닌 ID 등
            return null;
        }
    }

    private static DeviceState readState(LineSpec spec, int unit) throws Exception {
        return DeviceState.fromCode(spec.line().read(unit, ModbusRegisterMap.STATE, 1)[0]);
    }
}
