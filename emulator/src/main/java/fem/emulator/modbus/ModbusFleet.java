package fem.emulator.modbus;

import fem.common.modbus.ModbusRegisterMap;
import fem.common.wire.WireMessages.CommandAction;
import fem.emulator.EmulatorConfig;
import fem.emulator.device.SimulatedDevice;
import fem.emulator.device.TickLoop;
import com.ghgande.j2mod.modbus.ModbusException;
import com.ghgande.j2mod.modbus.slave.ModbusSlave;
import com.ghgande.j2mod.modbus.slave.ModbusSlaveFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * 공장 B: Modbus TCP 설비 N대.
 *
 * <p>라인 컨트롤러 1대 = TCP 포트 1개, 그 아래 설비 100대 = unit 1~100 (설계 문서 가정 4).
 * 400대면 포트 5020~5023 네 개를 연다.
 *
 * <p>Modbus는 설비가 보내는 것이 아니라 게이트웨이가 읽어 가는 구조라 MQTT처럼 중복·순서 장애를 주입하지 않는다.
 * 대신 폴링 특성상 같은 카운터를 두 번 읽는 중복은 자연스럽게 생긴다.
 */
public final class ModbusFleet implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ModbusFleet.class);
    /** 포트당 동시 연결 처리 스레드 수. 게이트웨이는 포트당 연결 1개만 쓴다. */
    private static final int POOL_SIZE = 4;

    private final EmulatorConfig config;
    private final long initialSeq;
    private final List<ModbusSlave> slaves = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();

    public ModbusFleet(EmulatorConfig config, long initialSeq) {
        this.config = config;
        this.initialSeq = initialSeq;
    }

    public void start() throws ModbusException {
        SplittableRandom random = new SplittableRandom();
        int ports = (config.modbusDevices() + ModbusRegisterMap.UNITS_PER_PORT - 1) / ModbusRegisterMap.UNITS_PER_PORT;
        for (int offset = 0; offset < ports; offset++) {
            int port = config.modbusBasePort() + offset;
            ModbusSlave slave = ModbusSlaveFactory.createTCPSlave(port, POOL_SIZE);
            int units = Math.min(ModbusRegisterMap.UNITS_PER_PORT, config.modbusDevices() - offset * ModbusRegisterMap.UNITS_PER_PORT);
            for (int unit = 1; unit <= units; unit++) {
                String id = ModbusRegisterMap.deviceId(EmulatorConfig.MODBUS_FACTORY, config.modbusBasePort(), port, unit);
                SimulatedDevice device = new SimulatedDevice(id, initialSeq, config.deviceFaultRate(), random.split());
                // 람다 안에서 regs를 참조해야 해서 배열 한 칸에 담는다 (생성자 인자로 콜백을 넘기는 순환 참조 해소)
                DeviceRegisters[] holder = new DeviceRegisters[1];
                holder[0] = new DeviceRegisters(unit, value -> onControl(device, holder[0], value));
                slave.addProcessImage(unit, holder[0].image());
                holder[0].update(device.current(System.currentTimeMillis()));
                threads.add(Thread.ofVirtual().name("modbus-" + id)
                        .start(new TickLoop(device, random.nextInt(1000), holder[0]::update)));
            }
            slave.open();
            slaves.add(slave);
            log.info("modbus line controller on port {} with {} units", port, units);
        }
    }

    /** 제어 레지스터 쓰기 → 설비에 적용 → 상태 레지스터를 바로 갱신 (게이트웨이가 곧바로 결과를 읽을 수 있게) */
    private static void onControl(SimulatedDevice device, DeviceRegisters regs, int value) {
        CommandAction action = switch (value) {
            case ModbusRegisterMap.CONTROL_STOP -> CommandAction.STOP;
            case ModbusRegisterMap.CONTROL_RESUME -> CommandAction.RESUME;
            default -> null;
        };
        if (action == null) {
            log.warn("{} ignore unknown control value {}", device.deviceId(), value);
            return;
        }
        log.info("{} control {} → {}", device.deviceId(), action, device.apply(action));
        regs.update(device.current(System.currentTimeMillis()));
    }

    @Override
    public void close() {
        threads.forEach(Thread::interrupt);
        slaves.forEach(ModbusSlaveFactory::close);
    }
}
