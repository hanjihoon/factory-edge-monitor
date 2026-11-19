package fem.server.application;

import fem.common.TelemetryRecord;
import fem.common.wire.WireMessages.Batch;
import fem.server.application.port.in.IngestBatchUseCase;
import fem.server.application.port.out.LiveUpdates;
import fem.server.application.port.out.TelemetryRepository;
import fem.server.domain.DeviceSnapshot;
import fem.server.domain.LatestPerDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

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

    public IngestService(TelemetryRepository repository, LiveUpdates live, TransactionTemplate tx) {
        this.repository = repository;
        this.live = live;
        this.tx = tx;
    }

    private record Saved(int inserted, List<DeviceSnapshot> changed) {
    }

    @Override
    public Result ingest(String gatewayId, String factoryId, Batch batch) {
        // 인증된 게이트웨이가 남의 공장 설비 데이터를 보내면 버린다. ack는 보내야 게이트웨이가 같은 묶음을 무한히 재전송하지 않는다.
        List<TelemetryRecord> valid = batch.records().stream()
                .filter(r -> factoryId.equals(r.factoryId()) && r.deviceId().startsWith(factoryId + "-"))
                .toList();
        int rejected = batch.records().size() - valid.size();
        if (rejected > 0) {
            log.warn("{} sent {} records outside factory {}, dropped", gatewayId, rejected, factoryId);
        }
        if (valid.isEmpty()) {
            return new Result(0, 0, rejected);
        }

        Saved saved = tx.execute(status -> new Saved(
                repository.insertNew(valid),
                repository.upsertCurrent(LatestPerDevice.of(valid))));

        // 여기부터는 커밋된 뒤다
        if (!saved.changed().isEmpty()) {
            live.devicesChanged(saved.changed());
        }
        return new Result(saved.inserted(), valid.size() - saved.inserted(), rejected);
    }
}
