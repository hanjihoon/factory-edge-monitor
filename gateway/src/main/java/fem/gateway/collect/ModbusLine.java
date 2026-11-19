package fem.gateway.collect;

import com.ghgande.j2mod.modbus.facade.ModbusTCPMaster;
import com.ghgande.j2mod.modbus.procimg.Register;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;

/**
 * 라인 컨트롤러(Modbus TCP 포트) 하나와의 연결. 폴링과 명령 쓰기가 같은 연결을 공유한다.
 *
 * <p>Modbus TCP는 요청-응답이 한 연결에서 순서대로 오가므로, 두 스레드가 동시에 쓰면 응답이 뒤섞인다.
 * 메서드를 synchronized로 두어 한 번에 요청 하나만 나가게 한다.
 * 실패하면 연결을 버리고 다음 호출에서 다시 연다.
 */
public final class ModbusLine implements AutoCloseable {

    private static final int TIMEOUT_MS = 1000;

    private final String host;
    private final int port;
    private ModbusTCPMaster master;

    public ModbusLine(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public int port() {
        return port;
    }

    public synchronized int[] read(int unit, int ref, int count) throws Exception {
        try {
            Register[] regs = connected().readMultipleRegisters(unit, ref, count);
            int[] v = new int[regs.length];
            for (int i = 0; i < regs.length; i++) v[i] = regs[i].getValue();
            return v;
        } catch (Exception e) {
            reset();
            throw e;
        }
    }

    /** FC06 단일 레지스터 쓰기 */
    public synchronized void write(int unit, int ref, int value) throws Exception {
        try {
            connected().writeSingleRegister(unit, ref, new SimpleRegister(value));
        } catch (Exception e) {
            reset();
            throw e;
        }
    }

    private ModbusTCPMaster connected() throws Exception {
        if (master == null) {
            // 네 번째 인자 reconnect=true는 "요청마다 TCP 연결을 새로 연다"는 뜻이라 false. 연결은 유지하고 실패 시에만 다시 연다
            ModbusTCPMaster m = new ModbusTCPMaster(host, port, TIMEOUT_MS, false);
            m.connect();
            master = m;
        }
        return master;
    }

    private void reset() {
        if (master != null) {
            master.disconnect();
            master = null;
        }
    }

    @Override
    public synchronized void close() {
        reset();
    }
}
