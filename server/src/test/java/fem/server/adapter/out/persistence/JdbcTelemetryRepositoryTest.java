package fem.server.adapter.out.persistence;

import fem.common.DeviceState;
import fem.common.Protocol;
import fem.common.TelemetryRecord;
import fem.common.wire.WireMessages.Batch;
import fem.server.application.IngestService;
import fem.server.application.port.in.IngestBatchUseCase.Result;
import fem.server.application.port.out.LiveUpdates;
import fem.server.application.port.out.TelemetryRepository;
import fem.server.domain.DeviceSnapshot;
import fem.server.domain.GatewayView;
import fem.server.domain.RejectedRecord;
import fem.server.domain.RemoteCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 수신 경로를 실제 DB로: IngestService + JdbcTelemetryRepository + 실제 트랜잭션 관리자.
 *
 * <p>"유실 0 · 중복 0"의 근거가 되는 SQL 규칙을 하나씩 확인한다.
 * <ol>
 *   <li>같은 (device_id, seq)는 몇 번 와도 한 번만 저장된다 (재전송, 묶음 안 중복, Modbus 재읽기, 동시 수신)
 *   <li>늦게 온 옛 seq는 이력에는 남고 현재 상태를 되돌리지 않는다
 *   <li>저장이 중간에 실패하면 중복 판정 기록까지 함께 롤백된다 → 재전송분이 "이미 봤다"로 버려지지 않는다
 * </ol>
 */
class JdbcTelemetryRepositoryTest extends TimescaleDbSupport {

    /** 보관 정책(30일)이 지우지 않도록 지금 기준 시각을 쓴다. 고정 과거 시각을 쓰면 보관 작업이 도는 순간 테스트 데이터가 사라진다 */
    private static final long BASE = (System.currentTimeMillis() / 1000) * 1000 - 3_600_000;

    private JdbcTelemetryRepository repo;
    private TransactionTemplate tx;
    private final List<List<DeviceSnapshot>> pushed = Collections.synchronizedList(new ArrayList<>());

    private final LiveUpdates live = new LiveUpdates() {
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
    };

    @BeforeEach
    void setUp() {
        // JdbcTemplate과 트랜잭션 관리자가 같은 DataSource를 써야 한 트랜잭션으로 묶인다
        repo = new JdbcTelemetryRepository(jdbc);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    private IngestService service(TelemetryRepository r) {
        return new IngestService(r, live, tx);
    }

    private static TelemetryRecord rec(String device, long seq, long deviceTs) {
        return new TelemetryRecord(device, device.substring(0, 1), Protocol.MQTT, seq, deviceTs, deviceTs + 5,
                DeviceState.RUNNING, 20.5, 0.3, 1500, 0);
    }

    private static TelemetryRecord rec(String device, long seq) {
        return rec(device, seq, BASE + seq * 1000);
    }

    private static Batch batch(List<TelemetryRecord> records) {
        return new Batch("gw-a", 1, records.size(), records);
    }

    private static Batch batch(TelemetryRecord... records) {
        return batch(List.of(records));
    }

    @Test
    void resent_batch_is_stored_only_once() {
        Batch b = batch(rec("A-0001", 1), rec("A-0001", 2), rec("A-0002", 1));

        assertEquals(new Result(3, 0, 0), service(repo).ingest("gw-a", "A", b));
        assertEquals(new Result(0, 3, 0), service(repo).ingest("gw-a", "A", b), "ack was lost, gateway sent it again");

        assertEquals(3, count("SELECT count(*) FROM telemetry"));
        // epoch ms → timestamptz 변환이 밀리초까지 맞는지
        assertEquals(BASE + 1_000, count(
                "SELECT (extract(epoch FROM device_ts) * 1000)::bigint FROM telemetry WHERE device_id = 'A-0001' AND seq = 1"));
    }

    @Test
    void duplicate_inside_one_batch_is_stored_once() {
        // 에뮬레이터 1% 중복 주입: 브로커가 같은 메시지를 두 번 전달 → 한 묶음에 같은 키가 두 번
        Result result = service(repo).ingest("gw-a", "A", batch(rec("A-0001", 7), rec("A-0001", 7)));

        assertEquals(1, result.inserted());
        assertEquals(1, count("SELECT count(*) FROM telemetry"));
    }

    @Test
    void same_seq_read_again_at_another_time_is_a_duplicate() {
        // Modbus는 0.5초마다 읽으므로 같은 샘플(seq)을 다른 시각에 두 번 읽는다.
        // hypertable 유니크 키는 시각을 포함해야 해서 이 경우를 못 잡는다 → 별도 판정표가 있는 이유
        service(repo).ingest("gw-b", "B", batch(new TelemetryRecord("B-0001", "B", Protocol.MODBUS, 5, BASE, BASE,
                DeviceState.RUNNING, 1, 1, 1, 0)));
        Result again = service(repo).ingest("gw-b", "B", batch(new TelemetryRecord("B-0001", "B", Protocol.MODBUS, 5, BASE + 500, BASE + 500,
                DeviceState.RUNNING, 1, 1, 1, 0)));

        assertEquals(0, again.inserted());
        assertEquals(1, count("SELECT count(*) FROM telemetry WHERE device_id = 'B-0001'"));
    }

    @Test
    void late_older_record_is_kept_in_history_but_does_not_move_current_state_back() {
        service(repo).ingest("gw-a", "A", batch(rec("A-0001", 10)));
        pushed.clear();

        // 백필로 늦게 도착한 옛 데이터
        service(repo).ingest("gw-a", "A", batch(rec("A-0001", 9)));

        assertEquals(2, count("SELECT count(*) FROM telemetry WHERE device_id = 'A-0001'"), "history keeps both");
        assertEquals(10, count("SELECT seq FROM device_current WHERE device_id = 'A-0001'"), "current stays at the newest");
        assertTrue(pushed.isEmpty(), "nothing changed on screen, so nothing is pushed");
    }

    @Test
    void newer_record_moves_current_state_forward_and_is_pushed() {
        service(repo).ingest("gw-a", "A", batch(rec("A-0001", 1), rec("A-0002", 1)));
        pushed.clear();

        service(repo).ingest("gw-a", "A", batch(rec("A-0001", 2), rec("A-0002", 1)));

        assertEquals(2, count("SELECT seq FROM device_current WHERE device_id = 'A-0001'"));
        assertEquals(List.of("A-0001"), pushed.getFirst().stream().map(DeviceSnapshot::deviceId).toList(),
                "only the row that actually changed is pushed");
    }

    @Test
    void failure_after_the_dedup_insert_rolls_back_the_dedup_key_too() {
        // 핵심 시나리오: 중복 판정표에 키를 넣은 뒤 현재 상태 갱신에서 실패.
        // 키가 남으면 재전송분이 "이미 봤다"로 버려져 유실된다 (Redis로 판정하면 생기는 문제와 같은 구조).
        TelemetryRepository failingOnce = new TelemetryRepository() {
            boolean failed;

            @Override
            public int insertNew(List<TelemetryRecord> records) {
                return repo.insertNew(records);
            }

            @Override
            public List<DeviceSnapshot> upsertCurrent(List<TelemetryRecord> latest) {
                if (!failed) {
                    failed = true;
                    throw new IllegalStateException("db connection lost");
                }
                return repo.upsertCurrent(latest);
            }

            @Override
            public List<DeviceSnapshot> findAllCurrent() {
                return repo.findAllCurrent();
            }

            @Override
            public void quarantine(String gatewayId, List<RejectedRecord> rejected) {
                repo.quarantine(gatewayId, rejected);
            }
        };
        Batch b = batch(rec("A-0001", 1), rec("A-0001", 2));

        assertThrows(IllegalStateException.class, () -> service(failingOnce).ingest("gw-a", "A", b));
        assertEquals(0, count("SELECT count(*) FROM telemetry_dedup"), "dedup keys are rolled back with the data");
        assertEquals(0, count("SELECT count(*) FROM telemetry"));

        // ack가 안 나갔으므로 게이트웨이가 같은 묶음을 다시 보낸다 → 이번엔 저장돼야 한다
        Result resent = service(failingOnce).ingest("gw-a", "A", b);

        assertEquals(new Result(2, 0, 0), resent, "resent records are stored, not dropped as duplicates");
        assertEquals(2, count("SELECT count(*) FROM telemetry"));
    }

    @Test
    void the_same_batch_arriving_on_two_connections_at_once_is_stored_once() throws Exception {
        // 재연결 직후 옛 연결의 묶음이 아직 처리 중인데 새 연결로 같은 묶음이 오는 경우.
        // 판정표 기본 키 충돌로 한쪽만 "처음 본 키"가 되어야 한다
        List<TelemetryRecord> records = new ArrayList<>();
        for (int d = 1; d <= 50; d++) {
            for (long s : LongStream.rangeClosed(1, 40).toArray()) records.add(rec("A-%04d".formatted(d), s));
        }
        Batch b = batch(records); // 2,000건
        CountDownLatch start = new CountDownLatch(1);

        CompletableFuture<Result> first = CompletableFuture.supplyAsync(() -> await(start, () -> service(repo).ingest("gw-a", "A", b)));
        CompletableFuture<Result> second = CompletableFuture.supplyAsync(() -> await(start, () -> service(repo).ingest("gw-a", "A", b)));
        start.countDown();

        assertEquals(2_000, first.get().inserted() + second.get().inserted(), "together they store each record once");
        assertEquals(2_000, count("SELECT count(*) FROM telemetry"));
        assertEquals(0, count("SELECT count(*) - count(DISTINCT (device_id, seq)) FROM telemetry"));
        assertEquals(40, count("SELECT min(seq) FROM device_current"));
    }

    @Test
    void batches_saved_in_parallel_touching_the_same_devices_in_different_orders_do_not_deadlock() throws Exception {
        // 서버는 한 게이트웨이의 묶음을 병렬로 저장한다. 복구 중 백필 묶음 8개가 같은 설비 200대를 서로 다른 순서로 담고
        // 동시에 들어온다. 현재 상태 행을 설비 ID 순으로 잠그지 않으면 PostgreSQL이 교착을 감지하고 한쪽을 실패시킨다
        int batches = 8, devices = 200;
        List<Batch> list = new ArrayList<>();
        for (int b = 0; b < batches; b++) {
            List<TelemetryRecord> records = new ArrayList<>();
            for (int d = 1; d <= devices; d++) records.add(rec("A-%04d".formatted(d), b + 1)); // 묶음 b는 seq b+1
            Collections.shuffle(records, new java.util.Random(b)); // 묶음마다 설비 순서가 다르다
            list.add(batch(records));
        }
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Result>> futures = list.stream()
                .map(b -> CompletableFuture.supplyAsync(() -> await(start, () -> service(repo).ingest("gw-a", "A", b))))
                .toList();
        start.countDown();

        long stored = 0;
        for (var f : futures) stored += f.get().inserted(); // 교착으로 하나라도 실패하면 여기서 예외
        assertEquals((long) batches * devices, stored);
        assertEquals((long) batches * devices, count("SELECT count(*) FROM telemetry"));
        // 저장이 끝난 순서와 상관없이 현재 상태는 가장 큰 seq
        assertEquals(devices, count("SELECT count(*) FROM device_current WHERE seq = ?", batches));
    }

    @Test
    void unstorable_record_is_quarantined_and_the_rest_is_stored() {
        TelemetryRecord unknownState = new TelemetryRecord("A-0002", "A", Protocol.MQTT, 1, BASE, BASE, null, 0, 0, 0, 0);

        Result result = service(repo).ingest("gw-a", "A", batch(rec("A-0001", 1), unknownState));

        assertEquals(new Result(1, 0, 1), result);
        assertEquals(1, count("SELECT count(*) FROM telemetry"));
        assertEquals(1, count("SELECT count(*) FROM ingest_rejected WHERE gateway_id = 'gw-a' AND payload LIKE '%A-0002%'"));
    }

    @Test
    void record_with_a_reset_device_clock_is_quarantined_instead_of_being_deleted_by_retention() {
        // 이 테스트를 처음 쓸 때 고정 과거 시각(1년 전)을 넣었더니, 저장했다고 보고된 행이 가끔 사라졌다.
        // 보관 정책 작업이 그 청크를 지웠기 때문이다. 운영에서는 "ack했는데 사라진" 유실이 된다
        long lastYear = BASE - 365L * 24 * 60 * 60 * 1000;
        TelemetryRecord resetClock = rec("A-0003", 1, lastYear);

        Result result = service(repo).ingest("gw-a", "A", batch(rec("A-0001", 1), resetClock));

        assertEquals(new Result(1, 0, 1), result);
        assertEquals(1, count("SELECT count(*) FROM ingest_rejected WHERE reason LIKE 'device time older than retention%'"),
                "kept with the reason, so an operator can see the clock problem");
    }

    @Test
    void purge_removes_only_dedup_keys_older_than_two_days() {
        jdbc.update("INSERT INTO telemetry_dedup (device_id, seq, received_at) VALUES ('A-0001', 1, now() - INTERVAL '3 days')");
        jdbc.update("INSERT INTO telemetry_dedup (device_id, seq, received_at) VALUES ('A-0001', 2, now() - INTERVAL '1 day')");

        repo.purgeDedup();

        assertEquals(List.of(2L), jdbc.queryForList("SELECT seq FROM telemetry_dedup", Long.class));
    }

    @Test
    void retention_and_compression_policies_are_registered() {
        // V1(보관 30일)과 V4(2일 뒤 압축)가 실제 TimescaleDB에 작업으로 등록되는지
        assertEquals(List.of("policy_compression", "policy_retention"), jdbc.queryForList(
                "SELECT proc_name FROM timescaledb_information.jobs WHERE hypertable_name = 'telemetry' ORDER BY proc_name",
                String.class));
    }

    private static <T> T await(CountDownLatch latch, java.util.function.Supplier<T> work) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        return work.get();
    }
}
