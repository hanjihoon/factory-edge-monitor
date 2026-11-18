package fem.emulator.device;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * 설비 한 대를 1초 주기로 돌리는 루프. 설비마다 가상 스레드 하나에서 실행한다.
 *
 * <p>"다음 실행 시각 = 이전 예정 시각 + 1초"로 계산한다 (지금 + 1초가 아니라).
 * 그래야 발행 지연(최대 수백 ms)이 끼어도 주기가 밀려 쌓이지 않는다.
 *
 * <p>phaseMs: 1,000대가 같은 밀리초에 동시에 깨어나면 순간 부하가 몰리므로 시작 시점을 0~999ms로 흩뜨린다.
 */
public final class TickLoop implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(TickLoop.class);
    public static final long PERIOD_MS = 1000;

    private final SimulatedDevice device;
    private final long phaseMs;
    private final Consumer<Reading> sink;

    public TickLoop(SimulatedDevice device, long phaseMs, Consumer<Reading> sink) {
        this.device = device;
        this.phaseMs = phaseMs;
        this.sink = sink;
    }

    @Override
    public void run() {
        long next = System.currentTimeMillis() + phaseMs;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                long wait = next - System.currentTimeMillis();
                if (wait > 0) {
                    Thread.sleep(wait);
                }
                try {
                    sink.accept(device.tick(System.currentTimeMillis()));
                } catch (RuntimeException e) {
                    // 한 번 실패로 설비가 영영 멈추면 안 된다. 기록만 하고 다음 주기로 간다.
                    log.warn("{} tick failed: {}", device.deviceId(), e.toString());
                }
                next += PERIOD_MS;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 종료 신호
        }
    }
}
