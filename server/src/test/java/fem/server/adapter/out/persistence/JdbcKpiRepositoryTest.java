package fem.server.adapter.out.persistence;

import fem.server.domain.KpiReport.DeviceKpi;
import fem.server.domain.KpiReport.FactoryKpi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 가동률 집계(V5 연속 집계 device_state_1m)를 실제 TimescaleDB로 확인한다 */
class JdbcKpiRepositoryTest extends TimescaleDbSupport {

    /** 2시간 전 정각(분 단위). 연속 집계 칸이 1분이라 분 경계에 맞춘다 */
    private static final Instant T0 = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.HOURS);

    private JdbcKpiRepository repo;

    @BeforeEach
    void setUp() {
        repo = new JdbcKpiRepository(JdbcClient.create(dataSource));
    }

    /** 설비 하나가 seconds초 동안 1초마다 state로 보고한 것을 원본에 넣는다 */
    private void samples(String device, String factory, int fromSec, int seconds, String state) {
        for (int s = fromSec; s < fromSec + seconds; s++) {
            jdbc.update("""
                            INSERT INTO telemetry (device_id, seq, factory_id, protocol, device_ts, gateway_ts,
                                                   state, temperature, vibration, rpm, alarm_code)
                            VALUES (?, ?, ?, 'MQTT', ?, ?, ?, 0, 0, 0, 0)""",
                    device, s, factory, Timestamp.from(T0.plusSeconds(s)), Timestamp.from(T0.plusSeconds(s)), state);
        }
    }

    /** 정책 작업(1분마다)을 기다리지 않고 지금 집계를 맞춘다. 트랜잭션 밖에서만 부를 수 있다 */
    private void refresh() {
        jdbc.execute("CALL refresh_continuous_aggregate('device_state_1m', NULL, NULL)");
    }

    @Test
    void availability_is_running_seconds_over_reported_seconds_per_factory_and_device() {
        samples("A-0001", "A", 0, 600, "RUNNING");                // 10분 내내 가동 → 100%
        samples("A-0002", "A", 0, 300, "RUNNING");                // 5분 가동, 3분 정지, 2분 고장 → 50%
        samples("A-0002", "A", 300, 180, "STOPPED");
        samples("A-0002", "A", 480, 120, "FAULT");
        samples("B-0001", "B", 0, 600, "FAULT");                  // 10분 내내 고장 → 0%
        refresh();

        Instant from = T0, to = T0.plus(1, ChronoUnit.HOURS);
        List<FactoryKpi> f = repo.byFactory(from, to);
        assertEquals(new FactoryKpi("A", 1200, 900, 180, 120, 75.0), f.get(0)); // (600 + 300) / 1200
        assertEquals(new FactoryKpi("B", 600, 0, 0, 600, 0.0), f.get(1));

        List<DeviceKpi> worst = repo.worstDevices(from, to, 2);
        assertEquals(List.of("B-0001", "A-0002"), worst.stream().map(DeviceKpi::deviceId).toList(), "lowest availability first");
        assertEquals(50.0, worst.get(1).availabilityPct());

        assertEquals(1, repo.hourly(from, to).stream().filter(p -> p.factoryId().equals("A")).count());
    }

    @Test
    void recent_minutes_not_yet_materialized_are_counted_from_the_raw_table() {
        // materialized_only = false: 정책 작업이 아직 돌지 않은 최근 데이터도 원본에서 바로 세어 합친다
        refresh(); // 지금까지를 집계해 두고
        samples("A-0003", "A", 3_600, 60, "RUNNING"); // 그 뒤에 들어온 1분 (집계 갱신 없음)
        List<FactoryKpi> f = repo.byFactory(T0, Instant.now());
        assertEquals(60, f.getFirst().samples());
    }
}
