package fem.gateway.ingest;

import fem.common.DeviceState;
import fem.common.Protocol;
import fem.common.TelemetryRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class IngestWriterTest {

    private Thread thread;

    @AfterEach
    void stop() throws InterruptedException {
        if (thread != null) {
            thread.interrupt();
            thread.join(2000);
        }
    }

    private static TelemetryRecord record(long seq) {
        return new TelemetryRecord("A-0001", "A", Protocol.MQTT, seq, 0, 0, DeviceState.RUNNING, 0, 0, 0, 0);
    }

    private IngestWriter start(IngestWriter.Store store, Runnable onAppended) {
        IngestWriter w = new IngestWriter(store, 100, 10, onAppended);
        thread = Thread.ofVirtual().start(w);
        return w;
    }

    @Test
    void callbacks_run_only_after_records_are_stored() throws Exception {
        List<Long> stored = new CopyOnWriteArrayList<>();
        List<Long> confirmedWhileStored = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(3);
        IngestWriter w = start(records -> records.forEach(r -> stored.add(r.seq())), () -> {});

        for (long s = 1; s <= 3; s++) {
            long seq = s;
            w.submit(record(seq), () -> {
                if (stored.contains(seq)) confirmedWhileStored.add(seq); // 콜백 시점에 이미 저장돼 있어야 한다
                done.countDown();
            });
        }

        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertEquals(List.of(1L, 2L, 3L), stored);
        assertEquals(List.of(1L, 2L, 3L), confirmedWhileStored);
        assertEquals(3, w.writtenCount());
    }

    @Test
    void failed_write_is_retried_and_not_confirmed_until_success() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        List<Long> stored = new CopyOnWriteArrayList<>();
        CountDownLatch confirmed = new CountDownLatch(1);
        IngestWriter w = start(records -> {
            if (attempts.incrementAndGet() == 1) throw new java.sql.SQLException("disk full");
            records.forEach(r -> stored.add(r.seq()));
        }, () -> {});

        w.submit(record(1), confirmed::countDown);

        assertFalse(confirmed.await(500, TimeUnit.MILLISECONDS), "must not confirm after a failed write");
        assertTrue(confirmed.await(3, TimeUnit.SECONDS));
        assertEquals(List.of(1L), stored, "written exactly once");
        assertEquals(2, attempts.get());
    }

    @Test
    void notifies_after_each_commit() throws Exception {
        CountDownLatch appended = new CountDownLatch(1);
        IngestWriter w = start(records -> {}, appended::countDown);

        w.submit(record(1), () -> {});

        assertTrue(appended.await(2, TimeUnit.SECONDS));
    }
}
