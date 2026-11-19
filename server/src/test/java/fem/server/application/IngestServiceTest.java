package fem.server.application;

import fem.common.DeviceState;
import fem.common.Protocol;
import fem.common.TelemetryRecord;
import fem.common.wire.WireMessages.Batch;
import fem.server.application.port.in.IngestBatchUseCase.Result;
import fem.server.application.port.out.LiveUpdates;
import fem.server.domain.DeviceSnapshot;
import fem.server.domain.GatewayView;
import fem.server.domain.RejectedRecord;
import fem.server.domain.RemoteCommand;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IngestServiceTest {

    private final InMemoryTelemetryRepository repo = new InMemoryTelemetryRepository();
    private final FakeTransactionManager txm = new FakeTransactionManager();
    private final List<List<DeviceSnapshot>> pushed = new ArrayList<>();
    private final IngestService service = new IngestService(repo, new LiveUpdates() {
        @Override
        public void devicesChanged(List<DeviceSnapshot> devices) {
            pushed.add(devices);
        }

        @Override
        public void commandChanged(RemoteCommand command) {
        }

        @Override
        public void gatewayChanged(GatewayView gateway) {
        }
    }, new TransactionTemplate(txm));

    private static TelemetryRecord r(String device, long seq) {
        return new TelemetryRecord(device, device.substring(0, 1), Protocol.MQTT, seq, seq, seq, DeviceState.RUNNING, 0, 0, 0, 0);
    }

    private static Batch batch(TelemetryRecord... records) {
        return new Batch("gw-a", 1, records.length, List.of(records));
    }

    @Test
    void stores_new_records_in_one_transaction_and_pushes_changes() {
        Result result = service.ingest("gw-a", "A", batch(r("A-0001", 1), r("A-0001", 2), r("A-0002", 1)));

        assertEquals(new Result(3, 0, 0), result);
        assertEquals(1, txm.commits);
        assertEquals(1, pushed.size());
        assertEquals(List.of(2L, 1L), pushed.getFirst().stream().map(DeviceSnapshot::seq).toList());
    }

    @Test
    void resent_batch_is_counted_as_duplicates_and_pushes_nothing() {
        service.ingest("gw-a", "A", batch(r("A-0001", 1), r("A-0001", 2)));

        Result again = service.ingest("gw-a", "A", batch(r("A-0001", 1), r("A-0001", 2)));

        assertEquals(new Result(0, 2, 0), again);
        assertEquals(2, repo.stored.size());
        assertEquals(1, pushed.size(), "no change pushed for duplicates");
    }

    @Test
    void late_older_record_is_stored_but_does_not_move_current_state_back() {
        service.ingest("gw-a", "A", batch(r("A-0001", 10)));

        service.ingest("gw-a", "A", batch(r("A-0001", 9))); // 순서 뒤바뀜으로 늦게 도착

        assertEquals(2, repo.stored.size(), "history keeps both");
        assertEquals(10, repo.findAllCurrent().getFirst().seq(), "current stays at the newest");
    }

    @Test
    void records_of_another_factory_are_rejected() {
        Result result = service.ingest("gw-a", "A", batch(r("A-0001", 1), r("B-0001", 1)));

        assertEquals(new Result(1, 0, 1), result);
        assertEquals(List.of("A-0001"), repo.stored.stream().map(TelemetryRecord::deviceId).toList());
        assertEquals(1, repo.quarantined.size(), "kept for inspection, not silently dropped");
    }

    @Test
    void unstorable_record_is_quarantined_and_the_rest_of_the_batch_is_saved() {
        // 새 버전 게이트웨이가 서버가 모르는 상태 값을 보내면 코덱이 null로 읽는다
        TelemetryRecord unknownState = new TelemetryRecord("A-0002", "A", Protocol.MQTT, 1, 1, 1, null, 0, 0, 0, 0);
        TelemetryRecord badTime = new TelemetryRecord("A-0003", "A", Protocol.MQTT, 1, -5, 1, DeviceState.RUNNING, 0, 0, 0, 0);

        Result result = service.ingest("gw-a", "A", batch(r("A-0001", 1), unknownState, badTime));

        assertEquals(new Result(1, 0, 2), result, "no exception → the batch is acked, no endless resend");
        assertEquals(1, txm.commits, "quarantine and data commit together, before the ack");
        assertEquals(List.of("missing or unknown field (deviceId/factoryId/protocol/state)", "timestamp out of range"),
                repo.quarantined.stream().map(RejectedRecord::reason).toList());
        assertTrue(repo.quarantined.getFirst().payload().contains("A-0002"), "original content is kept");
    }

    @Test
    void batch_with_only_bad_records_still_commits_the_quarantine() {
        TelemetryRecord noDevice = new TelemetryRecord(null, "A", Protocol.MQTT, 1, 1, 1, DeviceState.RUNNING, 0, 0, 0, 0);

        Result result = service.ingest("gw-a", "A", batch(noDevice));

        assertEquals(new Result(0, 0, 1), result);
        assertEquals(1, txm.commits);
        assertTrue(pushed.isEmpty());
    }

    @Test
    void failure_rolls_back_and_pushes_nothing() {
        repo.failNext = new IllegalStateException("db down");

        assertThrows(IllegalStateException.class, () -> service.ingest("gw-a", "A", batch(r("A-0001", 1))));

        assertEquals(1, txm.rollbacks);
        assertEquals(0, txm.commits);
        assertTrue(pushed.isEmpty());
    }
}
