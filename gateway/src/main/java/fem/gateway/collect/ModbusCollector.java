package fem.gateway.collect;

import fem.common.modbus.ModbusRegisterMap;
import fem.gateway.ingest.RecordSink;
import fem.gateway.normalize.ModbusNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * 공장 B 폴링. 라인 컨트롤러(포트)마다 가상 스레드 하나가 unit 1~100을 차례로 읽는다.
 *
 * <p>읽기 실패가 나면 그 라인의 이번 주기는 건너뛴다. 연결이 끊긴 상태에서 100대를 하나씩 시도하면
 * 타임아웃(1초) × 100 = 100초 동안 그 라인이 멈추기 때문이다.
 */
public final class ModbusCollector implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ModbusCollector.class);

    /** @param units 이 라인에 붙은 unit 수 (1~units) */
    public record LineSpec(ModbusLine line, int units) {
    }

    private final List<LineSpec> lines;
    private final ModbusNormalizer normalizer;
    private final RecordSink sink;
    private final DeviceActivity activity;
    private final LongSupplier clock;
    private final long pollMs;
    private final List<Thread> threads = new ArrayList<>();

    public ModbusCollector(List<LineSpec> lines, ModbusNormalizer normalizer, RecordSink sink,
                           DeviceActivity activity, LongSupplier clock, long pollMs) {
        this.lines = lines;
        this.normalizer = normalizer;
        this.sink = sink;
        this.activity = activity;
        this.clock = clock;
        this.pollMs = pollMs;
    }

    /** 설비 수로 라인 구성을 만든다. 400대 → 포트 4개 × 100 */
    public static List<LineSpec> layout(String host, int basePort, int devices) {
        List<LineSpec> out = new ArrayList<>();
        for (int offset = 0; offset * ModbusRegisterMap.UNITS_PER_PORT < devices; offset++) {
            int units = Math.min(ModbusRegisterMap.UNITS_PER_PORT, devices - offset * ModbusRegisterMap.UNITS_PER_PORT);
            out.add(new LineSpec(new ModbusLine(host, basePort + offset), units));
        }
        return out;
    }

    public void start() {
        for (LineSpec spec : lines) {
            threads.add(Thread.ofVirtual().name("poll-" + spec.line().port()).start(() -> loop(spec)));
        }
        log.info("polling {} modbus lines every {}ms", lines.size(), pollMs);
    }

    private void loop(LineSpec spec) {
        long next = System.currentTimeMillis();
        try {
            while (!Thread.currentThread().isInterrupted()) {
                pollOnce(spec);
                next += pollMs;
                long wait = next - System.currentTimeMillis();
                if (wait > 0) {
                    Thread.sleep(wait);
                } else {
                    next = System.currentTimeMillis(); // 밀렸으면 따라잡으려 몰아서 읽지 않고 지금부터 다시
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 한 라인을 한 바퀴 읽는다.
     *
     * @return 이번에 새로 수집한 레코드 수
     */
    int pollOnce(LineSpec spec) throws InterruptedException {
        int collected = 0;
        for (int unit = 1; unit <= spec.units(); unit++) {
            int[] regs;
            try {
                regs = spec.line().read(unit, 0, ModbusRegisterMap.TELEMETRY_COUNT);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                log.warn("line {} unit {} read failed, skip this cycle: {}", spec.line().port(), unit, e.toString());
                return collected;
            }
            long now = clock.getAsLong();
            var record = normalizer.normalize(spec.line().port(), unit, regs, now);
            if (record.isPresent()) {
                activity.seen(record.get().deviceId(), now);
                // 레지스터는 설비에 남아 있지 않으므로 다시 읽을 방법이 없다. ack 콜백이 필요 없다.
                sink.submit(record.get(), () -> {});
                collected++;
            }
        }
        return collected;
    }

    @Override
    public void close() {
        threads.forEach(Thread::interrupt);
        lines.forEach(s -> s.line().close());
    }
}
