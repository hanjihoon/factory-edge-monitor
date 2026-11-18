package fem.gateway.uplink;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class BackoffTest {

    @Test
    void delay_doubles_within_jitter_band_until_cap() {
        Backoff b = new Backoff(1000, 8000, new Random(3));
        long[] caps = {1000, 2000, 4000, 8000, 8000, 8000};

        for (long cap : caps) {
            long d = b.nextDelayMs();
            assertTrue(d >= cap / 2 && d <= cap, "delay " + d + " not in [" + cap / 2 + ", " + cap + "]");
        }
    }

    @Test
    void reset_starts_over() {
        Backoff b = new Backoff(1000, 30_000, new Random(3));
        for (int i = 0; i < 10; i++) b.nextDelayMs();

        b.reset();

        assertTrue(b.nextDelayMs() <= 1000);
    }

    @Test
    void many_failures_do_not_overflow() {
        Backoff b = new Backoff(500, 30_000, new Random(3));
        for (int i = 0; i < 200; i++) {
            long d = b.nextDelayMs();
            assertTrue(d > 0 && d <= 30_000, "delay " + d);
        }
    }
}
