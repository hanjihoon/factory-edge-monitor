package fem.gateway.outbox;

import fem.common.DeviceState;
import fem.common.Protocol;
import fem.common.TelemetryRecord;
import fem.gateway.wire.WireCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

class SqliteOutboxTest {

    @TempDir
    Path dir;
    private final AtomicLong clock = new AtomicLong(1_000);
    private SqliteOutbox outbox;

    private SqliteOutbox open(long maxRows) throws Exception {
        if (outbox != null) outbox.close();
        outbox = new SqliteOutbox(dir.resolve("buffer.db"), maxRows, new WireCodec(), clock::get);
        return outbox;
    }

    @AfterEach
    void close() throws Exception {
        if (outbox != null) outbox.close();
    }

    private static List<TelemetryRecord> records(long fromSeq, long toSeq) {
        return LongStream.rangeClosed(fromSeq, toSeq)
                .mapToObj(s -> new TelemetryRecord("A-0001", "A", Protocol.MQTT, s, s, s, DeviceState.RUNNING, 60, 2, 1500, 0))
                .toList();
    }

    private static List<Long> seqs(List<SqliteOutbox.Entry> entries) {
        return entries.stream().map(e -> e.record().seq()).toList();
    }

    @Test
    void fetches_in_insertion_order_with_increasing_ids() throws Exception {
        open(1000).append(records(1, 5));

        List<SqliteOutbox.Entry> all = outbox.fetchAfter(0, 10);

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), seqs(all));
        assertEquals(records(1, 1).getFirst(), all.getFirst().record());
        for (int i = 1; i < all.size(); i++) assertEquals(all.get(i - 1).id() + 1, all.get(i).id());
    }

    @Test
    void fetch_after_respects_cursor_and_limit() throws Exception {
        open(1000).append(records(1, 10));
        long thirdId = outbox.fetchAfter(0, 3).get(2).id();

        assertEquals(List.of(4L, 5L), seqs(outbox.fetchAfter(thirdId, 2)));
    }

    @Test
    void fetch_range_stops_at_upper_bound() throws Exception {
        open(1000).append(records(1, 10));
        long fourthId = outbox.fetchAfter(0, 4).get(3).id();

        assertEquals(List.of(1L, 2L, 3L, 4L), seqs(outbox.fetchRange(0, fourthId, 100)));
        assertEquals(List.of(3L, 4L), seqs(outbox.fetchRange(outbox.fetchAfter(0, 2).get(1).id(), fourthId, 100)));
    }

    @Test
    void max_id_is_zero_when_empty_and_last_id_otherwise() throws Exception {
        open(1000);
        assertEquals(0, outbox.maxId());

        outbox.append(records(1, 3));

        assertEquals(outbox.fetchAfter(0, 10).getLast().id(), outbox.maxId());
    }

    @Test
    void ack_deletes_exactly_the_given_range() throws Exception {
        open(1000).append(records(1, 6));
        List<SqliteOutbox.Entry> all = outbox.fetchAfter(0, 10);

        // 뒤쪽 범위(실시간 묶음)를 먼저 지워도 앞쪽(밀린 데이터)은 남는다
        assertEquals(2, outbox.deleteRange(all.get(4).id(), all.get(5).id()));
        assertEquals(List.of(1L, 2L, 3L, 4L), seqs(outbox.fetchAfter(0, 10)));

        assertEquals(2, outbox.deleteRange(all.get(1).id(), all.get(2).id()));
        assertEquals(List.of(1L, 4L), seqs(outbox.fetchAfter(0, 10)), "gaps are fine, order is kept");
        assertEquals(2, outbox.stats().count());
    }

    @Test
    void over_capacity_drops_exactly_the_oldest_even_with_gaps() throws Exception {
        open(6).append(records(1, 6));
        List<SqliteOutbox.Entry> all = outbox.fetchAfter(0, 10);
        outbox.deleteRange(all.get(1).id(), all.get(2).id()); // 2, 3이 빠져 id에 빈틈: 1, 4, 5, 6

        outbox.append(records(7, 10)); // 8건 → 상한 6, 가장 오래된 2건(1, 4)을 버려야 한다

        assertEquals(List.of(5L, 6L, 7L, 8L, 9L, 10L), seqs(outbox.fetchAfter(0, 100)));
        assertEquals(2, outbox.stats().droppedTotal());
    }

    @Test
    void unacked_records_survive_restart() throws Exception {
        open(1000).append(records(1, 3));

        open(1000); // 게이트웨이 재시작

        assertEquals(List.of(1L, 2L, 3L), seqs(outbox.fetchAfter(0, 10)));
        assertEquals(3, outbox.stats().count());
    }

    @Test
    void ids_are_never_reused_even_after_everything_is_acked_and_restarted() throws Exception {
        open(1000).append(records(1, 3));
        List<SqliteOutbox.Entry> all = outbox.fetchAfter(0, 10);
        long lastId = all.getLast().id();
        outbox.deleteRange(all.getFirst().id(), lastId);

        open(1000).append(records(4, 4));

        // 서버가 이미 lastId까지 ack 했으므로 새 레코드 id가 그 이하이면 안 된다
        assertTrue(outbox.fetchAfter(0, 10).getFirst().id() > lastId);
    }

    @Test
    void stats_report_oldest_creation_time() throws Exception {
        open(1000);
        assertEquals(new SqliteOutbox.Stats(0, 0, 0), outbox.stats());

        outbox.append(records(1, 1));
        clock.set(5_000);
        outbox.append(records(2, 2));

        assertEquals(1_000, outbox.stats().oldestCreatedAt());
        assertEquals(2, outbox.stats().count());
    }

    @Test
    void over_capacity_drops_oldest_and_counts_them() throws Exception {
        open(5).append(records(1, 4));
        outbox.append(records(5, 8));

        assertEquals(List.of(4L, 5L, 6L, 7L, 8L), seqs(outbox.fetchAfter(0, 100)));
        assertEquals(new SqliteOutbox.Stats(5, 1_000, 3), outbox.stats());
    }
}
