package fem.server.application;

import fem.server.application.port.out.KpiRepository;
import fem.server.domain.KpiReport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.function.LongSupplier;

/** 최근 N시간 가동률 보고를 만든다. 계산은 DB 연속 집계가 하고, 여기서는 구간을 정하고 묶기만 한다. */
@Service
public class KpiService {

    /** 보관 기간(30일) 안에서 화면이 쓸 만한 범위. 너무 길면 시간대 막대가 많아진다 */
    static final int MAX_HOURS = 24 * 7;
    private static final int WORST_LIMIT = 5;

    private final KpiRepository repository;
    private final LongSupplier clock;

    @Autowired
    public KpiService(KpiRepository repository) {
        this(repository, System::currentTimeMillis);
    }

    KpiService(KpiRepository repository, LongSupplier clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public KpiReport report(int hours) {
        int h = Math.clamp(hours, 1, MAX_HOURS);
        Instant to = Instant.ofEpochMilli(clock.getAsLong());
        Instant from = to.minus(h, ChronoUnit.HOURS);
        return new KpiReport(h, repository.byFactory(from, to), repository.hourly(from, to),
                repository.worstDevices(from, to, WORST_LIMIT));
    }
}
