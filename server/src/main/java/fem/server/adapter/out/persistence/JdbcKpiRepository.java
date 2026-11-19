package fem.server.adapter.out.persistence;

import fem.server.application.port.out.KpiRepository;
import fem.server.domain.KpiReport.DeviceKpi;
import fem.server.domain.KpiReport.FactoryKpi;
import fem.server.domain.KpiReport.HourPoint;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 가동률은 원본(telemetry)이 아니라 1분 연속 집계(device_state_1m, V5)에서 센다.
 * 24시간 기준으로 원본 8,640만 행 대신 집계 약 144만 행을 읽는다.
 *
 * <p>구간은 1분 단위로 맞춰 자른다 (집계 칸이 1분이라 그보다 잘게 자를 수 없다).
 * 가동률 = running ÷ samples × 100, 소수 1자리. samples가 0이면 0.
 */
@Repository
public class JdbcKpiRepository implements KpiRepository {

    /** 0으로 나누지 않도록 NULLIF. 결과는 소수 1자리 퍼센트 */
    private static final String PCT = "coalesce(round(100.0 * sum(running) / nullif(sum(samples), 0), 1), 0)";

    private final JdbcClient jdbc;

    public JdbcKpiRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<FactoryKpi> byFactory(Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT factory_id, sum(samples) AS samples, sum(running) AS running,
                               sum(stopped) AS stopped, sum(fault) AS fault, %s AS pct
                        FROM device_state_1m WHERE bucket >= ? AND bucket < ?
                        GROUP BY factory_id ORDER BY factory_id""".formatted(PCT))
                .params(Timestamp.from(from), Timestamp.from(to))
                .query((rs, i) -> new FactoryKpi(rs.getString("factory_id"), rs.getLong("samples"), rs.getLong("running"),
                        rs.getLong("stopped"), rs.getLong("fault"), rs.getDouble("pct")))
                .list();
    }

    @Override
    public List<HourPoint> hourly(Instant from, Instant to) {
        return jdbc.sql("""
                        SELECT factory_id, time_bucket(INTERVAL '1 hour', bucket) AS hour, sum(samples) AS samples, %s AS pct
                        FROM device_state_1m WHERE bucket >= ? AND bucket < ?
                        GROUP BY factory_id, hour ORDER BY hour, factory_id""".formatted(PCT))
                .params(Timestamp.from(from), Timestamp.from(to))
                .query((rs, i) -> new HourPoint(rs.getString("factory_id"), rs.getTimestamp("hour").getTime(),
                        rs.getLong("samples"), rs.getDouble("pct")))
                .list();
    }

    @Override
    public List<DeviceKpi> worstDevices(Instant from, Instant to, int limit) {
        return jdbc.sql("""
                        SELECT device_id, factory_id, sum(samples) AS samples, sum(fault) AS fault, %s AS pct
                        FROM device_state_1m WHERE bucket >= ? AND bucket < ?
                        GROUP BY device_id, factory_id
                        ORDER BY pct ASC, fault DESC, device_id LIMIT ?""".formatted(PCT))
                .params(Timestamp.from(from), Timestamp.from(to), limit)
                .query((rs, i) -> new DeviceKpi(rs.getString("device_id"), rs.getString("factory_id"),
                        rs.getLong("samples"), rs.getLong("fault"), rs.getDouble("pct")))
                .list();
    }
}
