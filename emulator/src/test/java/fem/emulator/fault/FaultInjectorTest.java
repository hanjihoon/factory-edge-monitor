package fem.emulator.fault;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class FaultInjectorTest {

    /** 확률 0과 1만 쓰면 무작위 값과 상관없이 결과가 정해진다. */
    private static FaultInjector<Integer> injector(double dup, double reorder, int delay) {
        return new FaultInjector<>(dup, reorder, delay, new Random(7));
    }

    @Test
    void passes_messages_through_when_no_fault() {
        FaultInjector<Integer> f = injector(0, 0, 0);

        assertEquals(List.of(1), f.next(1).messages());
        assertEquals(List.of(2), f.next(2).messages());
        assertEquals(0, f.next(3).delayMs());
    }

    @Test
    void duplicates_every_message_at_rate_one() {
        FaultInjector<Integer> f = injector(1, 0, 0);

        assertEquals(List.of(5, 5), f.next(5).messages());
    }

    @Test
    void reorder_swaps_adjacent_messages() {
        FaultInjector<Integer> f = injector(0, 1, 0);

        assertEquals(List.of(), f.next(1).messages());     // 1은 붙잡힘
        assertEquals(List.of(2, 1), f.next(2).messages()); // 2 다음에 1
        assertEquals(List.of(), f.next(3).messages());
        assertEquals(List.of(4, 3), f.next(4).messages());
    }

    @Test
    void every_message_is_eventually_emitted_under_mixed_faults() {
        // 장애를 섞어도 빠지는 메시지는 없어야 한다 (중복·순서만 흐트러짐)
        FaultInjector<Integer> f = injector(0.3, 0.3, 0);
        List<Integer> emitted = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) emitted.addAll(f.next(i).messages());
        emitted.addAll(f.next(1001).messages()); // 마지막에 붙잡혀 있을 수 있는 것을 밀어낸다

        for (int i = 1; i <= 1000; i++) assertTrue(emitted.contains(i), "missing " + i);
        assertTrue(emitted.size() > 1001, "some duplicates expected");
    }

    @Test
    void delay_stays_within_bound() {
        FaultInjector<Integer> f = injector(0, 0, 300);
        for (int i = 0; i < 500; i++) {
            int d = f.next(i).delayMs();
            assertTrue(d >= 0 && d <= 300, "delay " + d);
        }
    }
}
