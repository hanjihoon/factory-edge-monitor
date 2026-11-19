package fem.gateway.ingest;

import fem.common.TelemetryRecord;

/** 수집기가 정규화한 레코드를 넘기는 곳. 실제 구현은 {@link IngestWriter}. */
@FunctionalInterface
public interface RecordSink {

    /**
     * @param onDurable 디스크에 커밋된 뒤 호출된다 (MQTT 수동 ack 용)
     */
    void submit(TelemetryRecord record, Runnable onDurable) throws InterruptedException;
}
