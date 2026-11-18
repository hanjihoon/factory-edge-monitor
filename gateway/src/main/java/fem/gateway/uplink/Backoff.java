package fem.gateway.uplink;

import java.util.random.RandomGenerator;

/**
 * 재연결 대기 시간. 실패할 때마다 두 배로 늘리되 상한을 둔다 (지수 백오프).
 *
 * <p>지터: 회선이 복구되는 순간 여러 게이트웨이가 정확히 같은 시각에 몰려 붙지 않도록
 * 대기 시간을 [상한의 절반, 상한] 사이에서 무작위로 고른다 ("equal jitter").
 */
public final class Backoff {

    private final long baseMs;
    private final long maxMs;
    private final RandomGenerator random;
    private int attempt;

    public Backoff(long baseMs, long maxMs, RandomGenerator random) {
        this.baseMs = baseMs;
        this.maxMs = maxMs;
        this.random = random;
    }

    public long nextDelayMs() {
        // 1L << 20 이상은 의미가 없고 오버플로만 위험하므로 지수를 20에서 자른다
        long cap = Math.min(maxMs, baseMs * (1L << Math.min(attempt, 20)));
        attempt++;
        long half = cap / 2;
        return half + random.nextLong(cap - half + 1);
    }

    /** 연결에 성공하면 처음부터 다시 */
    public void reset() {
        attempt = 0;
    }
}
