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
    void ack_deletes_only_up_to_given_id() throws Exception {
        open(1000).append(records(1, 5));
        long secondId = outbox.fetchAfter(0, 2).get(1).id();

        assertEquals(2, outbox.deleteUpTo(secondId));

        assertEquals(List.of(3L, 4L, 5L), seqs(outbox.fetchAfter(0, 10)));
        assertEquals(3, outbox.stats().count());
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
        long lastId = outbox.fetchAfter(0, 10).getLast().id();
        outbox.deleteUpTo(lastId);

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
