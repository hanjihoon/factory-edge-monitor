package fem.emulator.fault;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 설비 한 대의 발행 흐름에 중복·순서 뒤바뀜·지연을 섞는다. 설비마다 하나씩 만든다 (상태를 가진다).
 *
 * <ul>
 *   <li><b>중복</b>: 같은 메시지를 연달아 두 번 내보낸다. (QoS 1 재전송과 같은 효과)</li>
 *   <li><b>순서 뒤바뀜</b>: 이번 메시지를 붙잡아 두었다가 다음 메시지 <i>뒤에</i> 내보낸다.
 *       결과적으로 seq 5, 4 순서로 도착한다.</li>
 *   <li><b>지연</b>: 내보내기 전에 0~maxDelayMs 동안 기다리게 한다.</li>
 * </ul>
 *
 * <p>내보낼 메시지와 지연 시간을 {@link Plan}으로 돌려줄 뿐 직접 보내지는 않는다.
 * 그래야 네트워크 없이 테스트할 수 있다.
 */
public final class FaultInjector<T> {

    private final double duplicateRate;
    private final double reorderRate;
    private final int maxDelayMs;
    private final RandomGenerator random;

    /** 순서 뒤바뀜을 위해 붙잡아 둔 메시지. 없으면 null */
    private T held;

    public FaultInjector(double duplicateRate, double reorderRate, int maxDelayMs, RandomGenerator random) {
        this.duplicateRate = duplicateRate;
        this.reorderRate = reorderRate;
        this.maxDelayMs = maxDelayMs;
        this.random = random;
    }

    /** 장애 없이 그대로 통과시키는 주입기 */
    public static <T> FaultInjector<T> none() {
        return new FaultInjector<>(0, 0, 0, RandomGenerator.getDefault());
    }

    /**
     * @param messages 이 순서대로 보낸다. 비어 있을 수 있다 (붙잡아 둔 경우)
     * @param delayMs  보내기 전에 기다릴 시간
     */
    public record Plan<T>(List<T> messages, int delayMs) {
    }

    public Plan<T> next(T message) {
        List<T> out = new ArrayList<>(3);
        // 이미 하나를 붙잡고 있으면 또 붙잡지 않는다. 두 개 이상 쌓이면 지연이 계속 커지기 때문이다.
        if (held == null && random.nextDouble() < reorderRate) {
            held = message;
        } else {
            out.add(message);
            if (random.nextDouble() < duplicateRate) {
                out.add(message);
            }
            if (held != null) {
                out.add(held); // 붙잡아 둔 이전 메시지를 이번 메시지 뒤에 → 순서 뒤바뀜
                held = null;
            }
        }
        int delay = maxDelayMs > 0 ? random.nextInt(maxDelayMs + 1) : 0;
        return new Plan<>(List.copyOf(out), delay);
    }
}
