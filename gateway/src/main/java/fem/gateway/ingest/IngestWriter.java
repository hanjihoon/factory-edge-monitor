package fem.gateway.ingest;

import fem.common.TelemetryRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 수집기 → 디스크(outbox) 사이의 그룹 커밋.
 *
 * <p>레코드마다 커밋(fsync)하면 초당 600건을 감당하기 어렵다. 큐에 모았다가 한 번에 커밋한다.
 * 커밋이 끝난 레코드에 대해서만 {@code onDurable} 콜백을 부른다.
 * MQTT 수집기는 이 콜백에서 브로커에 PUBACK을 보낸다 (수동 ack) → 디스크에 쓰기 전에 죽으면
 * 브로커가 그 메시지를 다시 보내 준다. 즉 브로커 → 게이트웨이 디스크 구간도 유실이 없다.
 *
 * <p>큐가 가득 차면 {@link #submit}이 막힌다 (배압). 디스크가 느려지면 수집도 함께 느려지는 편이
 * 메모리에 무한정 쌓다가 죽는 것보다 낫다.
 */
public final class IngestWriter implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(IngestWriter.class);
    private static final long RETRY_DELAY_MS = 1000;

    /** 디스크 저장소. SqliteOutbox::append를 넘긴다 (테스트에서는 가짜) */
    @FunctionalInterface
    public interface Store {
        void append(List<TelemetryRecord> records) throws Exception;
    }

    private record Pending(TelemetryRecord record, Runnable onDurable) {
    }

    private final Store store;
    private final int maxBatch;
    private final Runnable onAppended;
    private final BlockingQueue<Pending> queue;
    private final AtomicLong written = new AtomicLong();

    /**
     * @param onAppended 커밋할 때마다 호출. 업링크 전송기를 깨우는 데 쓴다
     */
    public IngestWriter(Store store, int queueCapacity, int maxBatch, Runnable onAppended) {
        this.store = store;
        this.maxBatch = maxBatch;
        this.onAppended = onAppended;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
    }

    /** 큐에 넣는다. 가득 찼으면 자리가 날 때까지 기다린다. */
    public void submit(TelemetryRecord record, Runnable onDurable) throws InterruptedException {
        queue.put(new Pending(record, onDurable));
    }

    public long writtenCount() {
        return written.get();
    }

    @Override
    public void run() {
        List<Pending> batch = new ArrayList<>(maxBatch);
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Pending first = queue.poll(100, TimeUnit.MILLISECONDS);
                if (first == null) continue;
                batch.add(first);
                queue.drainTo(batch, maxBatch - 1); // 기다리지 않고 지금 쌓여 있는 만큼 더 가져온다
                writeUntilSuccess(batch.stream().map(Pending::record).toList());
                for (Pending p : batch) {
                    try {
                        p.onDurable().run();
                    } catch (RuntimeException e) {
                        log.warn("onDurable callback failed: {}", e.toString());
                    }
                }
                written.addAndGet(batch.size());
                batch.clear();
                onAppended.run();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 디스크 쓰기가 실패하면 성공할 때까지 같은 묶음을 다시 쓴다. 그동안 콜백을 부르지 않으므로
     * MQTT 쪽 메시지는 브로커에 남아 있다. 버리고 넘어가면 유실이 되므로 포기하지 않는다.
     */
    private void writeUntilSuccess(List<TelemetryRecord> records) throws InterruptedException {
        while (true) {
            try {
                store.append(records);
                return;
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                log.error("outbox append failed for {} records, retry in {}ms: {}", records.size(), RETRY_DELAY_MS, e.toString());
                Thread.sleep(RETRY_DELAY_MS);
            }
        }
    }
}
