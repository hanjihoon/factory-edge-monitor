package fem.emulator.modbus;

import fem.common.modbus.ModbusRegisterMap;
import fem.emulator.device.Reading;
import com.ghgande.j2mod.modbus.procimg.ProcessImage;
import com.ghgande.j2mod.modbus.procimg.SimpleProcessImage;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;

import java.util.function.IntConsumer;

/**
 * Modbus 설비 한 대(unit 하나)의 Holding Register 0~10.
 *
 * <p>측정 스레드는 {@link #update}로 0~6번을 덮어쓴다. 게이트웨이가 10번(제어)에 값을 쓰면
 * {@link ControlRegister}가 그 즉시 콜백을 부르고 값을 0으로 되돌린다.
 *
 * <p>알려진 한계: 0~6번을 하나씩 쓰므로, 게이트웨이가 정확히 그 사이에 읽으면 새 seq와 이전 온도가 섞일 수 있다.
 * 쓰기는 1초에 한 번, 마이크로초 단위로 끝나므로 확률이 매우 낮고, 섞여도 값 하나가 1초 전 값일 뿐이라 감수한다.
 */
public final class DeviceRegisters {

    private final SimpleProcessImage image;
    private final SimpleRegister[] registers = new SimpleRegister[ModbusRegisterMap.TOTAL_COUNT];

    /** @param onControl 제어 레지스터에 0이 아닌 값이 써졌을 때 호출된다 (값 그대로 전달) */
    public DeviceRegisters(int unit, IntConsumer onControl) {
        image = new SimpleProcessImage(unit);
        for (int i = 0; i < registers.length; i++) {
            registers[i] = i == ModbusRegisterMap.CONTROL ? new ControlRegister(onControl) : new SimpleRegister(0);
            image.addRegister(registers[i]);
        }
    }

    public void update(Reading r) {
        int[] values = ModbusRegisterMap.encode(new ModbusRegisterMap.Sample(
                r.seq(), r.state(), r.temperature(), r.vibration(), r.rpm(), r.alarmCode()));
        // 카운터(0~1)를 마지막에 쓴다. 게이트웨이가 쓰는 도중에 읽어도 "새 카운터 + 옛 값"보다는
        // "옛 카운터 + 새 값"이 되도록 해서, 새 seq에 옛 값이 붙는 경우를 줄인다.
        for (int i = ModbusRegisterMap.TELEMETRY_COUNT - 1; i >= 0; i--) {
            registers[i].setValue(values[i]);
        }
    }

    public ProcessImage image() {
        return image;
    }

    /**
     * 쓰기를 가로채는 제어 레지스터.
     * j2mod는 FC06(단일 쓰기)에서 setValue(byte[]), FC16(다중 쓰기)에서 setValue(int)를 부르므로 모두 덮어쓴다.
     */
    static final class ControlRegister extends SimpleRegister {
        private final IntConsumer onControl;

        ControlRegister(IntConsumer onControl) {
            super(0);
            this.onControl = onControl;
        }

        @Override
        public synchronized void setValue(int v) {
            // 부모 생성자가 setValue를 부를 수 있어 onControl이 아직 null일 수 있다
            if (onControl != null && (v & 0xFFFF) != ModbusRegisterMap.CONTROL_NONE) {
                onControl.accept(v & 0xFFFF);
            }
            super.setValue(ModbusRegisterMap.CONTROL_NONE); // 처리 후 0으로 되돌린다 (설계 문서 6장)
        }

        @Override
        public synchronized void setValue(short s) {
            setValue(s & 0xFFFF);
        }

        @Override
        public synchronized void setValue(byte[] bytes) {
            setValue(((bytes[0] & 0xFF) << 8) | (bytes[1] & 0xFF));
        }
    }
}
