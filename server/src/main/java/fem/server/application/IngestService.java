package fem.server.application;

import fem.common.TelemetryRecord;
import fem.common.wire.WireMessages.Batch;
import fem.server.application.port.in.IngestBatchUseCase;
import fem.server.application.port.out.LiveUpdates;
import fem.server.application.port.out.TelemetryRepository;
import fem.server.domain.DeviceSnapshot;
import fem.server.domain.LatestPerDevice;
import fem.server.domain.RecordCheck;
import fem.server.domain.RejectedRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * 게이트웨이 묶음 저장. "중복 제거 저장 → 현재 상태 갱신"을 한 트랜잭션으로 묶는다.
 *
 * <p>트랜잭션을 애너테이션 대신 TransactionTemplate으로 쓴 이유: 화면 알림({@link LiveUpdates})은
 * 커밋이 끝난 뒤에 보내야 한다. 커밋 전에 화면에 보였다가 롤백되면 화면과 DB가 어긋난다.
 * 코드에서 "커밋 → 알림" 순서가 그대로 보이게 하려는 것이다.
 */
@Service
public class IngestService implements IngestBatchUseCase {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final TelemetryRepository repository;
    private final LiveUpdates live;
    private final TransactionTemplate tx;
    private final LongSupplier clock;

    @Autowired
    public IngestService(TelemetryRepository repository, LiveUpdates live, TransactionTemplate tx) {
        this(repository, live, tx, System::currentTimeMillis);
    }

    /** 테스트에서 현재 시각을 고정하려고 둔다 (보관 기간 판정이 시각에 의존) */
    IngestService(TelemetryRepository repository, LiveUpdates live, TransactionTemplate tx, LongSupplier clock) {
        this.repository = repository;
        this.live = live;
        this.tx = tx;
        this.clock = clock;
    }

    private record Saved(int inserted, List<DeviceSnapshot> changed) {
    }

    @Override
    public Result ingest(String gatewayId, String factoryId, Batch batch) {
        // 몇 번을 다시 보내도 저장될 수 없는 레코드(빈 필드, 남의 공장, 범위 밖 시각, 보관 기간보다 옛 시각)는 격리 테이블로 보낸다.
        // 예외로 처리하면 ack가 안 나가 게이트웨이가 같은 묶음을 끝없이 다시 보내고 그 뒤 데이터까지 막힌다 (RecordCheck).
        long now = clock.getAsLong();
        List<TelemetryRecord> valid = new ArrayList<>(batch.records().size());
        List<RejectedRecord> rejected = new ArrayList<>();
        for (TelemetryRecord r : batch.records()) {
            Optional<String> problem = RecordCheck.problem(r, factoryId, now);
            if (problem.isPresent()) rejected.add(new RejectedRecord(problem.get(), String.valueOf(r)));
            else valid.add(r);
        }
        if (!rejected.isEmpty()) {
            log.warn("{} sent {} records that cannot be stored, quarantined (first: {})",
                    gatewayId, rejected.size(), rejected.getFirst().reason());
        }

        // 격리도 같은 트랜잭션: 커밋된 뒤에야 ack가 나가므로 격리 기록이 사라진 채 게이트웨이가 지우는 일이 없다
        Saved saved = tx.execute(status -> {
            if (!rejected.isEmpty()) repository.quarantine(gatewayId, rejected);
            if (valid.isEmpty()) return new Saved(0, List.of());
            return new Saved(repository.insertNew(valid), repository.upsertCurrent(LatestPerDevice.of(valid)));
        });

        // 여기부터는 커밋된 뒤다
        if (!saved.changed().isEmpty()) {
            live.devicesChanged(saved.changed());
        }
        return new Result(saved.inserted(), valid.size() - saved.inserted(), rejected.size());
    }
}
