package fem.server.adapter.out.persistence;

import fem.common.TelemetryRecord;
import fem.server.application.port.out.TelemetryRepository;
import fem.server.domain.DeviceSnapshot;
import fem.server.domain.RejectedRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Function;

/**
 * TimescaleDB(PostgreSQL) 저장소.
 *
 * <p>성능: 묶음(최대 500건)을 행마다 INSERT 하면 왕복이 500번이다. 열마다 배열 하나로 보내고
 * {@code unnest}로 펼쳐 한 문장에 넣는다. 왕복 1번, 실행 계획 1번.
 */
@Repository
public class JdbcTelemetryRepository implements TelemetryRepository {

    private static final Logger log = LoggerFactory.getLogger(JdbcTelemetryRepository.class);

    /**
     * 1) incoming: 배열을 행으로 펼치고, 묶음 안 중복을 DISTINCT ON으로 하나만 남긴다.
     * 2) fresh: 중복 판정 테이블에 넣는다. 이미 있으면 DO NOTHING → RETURNING에 안 나온다 = 처음 보는 키만 남는다.
     * 3) 처음 보는 키만 hypertable에 넣는다.
     * 세 단계가 한 문장이라 동시에 같은 키가 들어와도 둘 중 하나만 fresh가 된다 (기본 키 충돌로 판정).
     */
    private static final String INSERT_NEW = """
            WITH incoming AS (
                SELECT DISTINCT ON (device_id, seq) *
                FROM unnest(?::text[], ?::bigint[], ?::text[], ?::text[], ?::bigint[], ?::bigint[],
                            ?::text[], ?::float8[], ?::float8[], ?::int[], ?::int[])
                     AS t(device_id, seq, factory_id, protocol, device_ts_ms, gateway_ts_ms,
                          state, temperature, vibration, rpm, alarm_code)
            ), fresh AS (
                INSERT INTO telemetry_dedup (device_id, seq)
                SELECT device_id, seq FROM incoming
                ON CONFLICT DO NOTHING
                RETURNING device_id, seq
            )
            INSERT INTO telemetry (device_id, seq, factory_id, protocol, device_ts, gateway_ts,
                                   state, temperature, vibration, rpm, alarm_code)
            SELECT i.device_id, i.seq, i.factory_id, i.protocol,
                   to_timestamp(i.device_ts_ms / 1000.0), to_timestamp(i.gateway_ts_ms / 1000.0),
                   i.state, i.temperature, i.vibration, i.rpm, i.alarm_code
            FROM incoming i JOIN fresh f USING (device_id, seq)
            """;

    /** seq가 저장된 값보다 클 때만 갱신 (WHERE 절). 바뀐 행만 RETURNING으로 돌려받아 화면에 보낸다. */
    private static final String UPSERT_CURRENT = """
            INSERT INTO device_current AS c (device_id, factory_id, seq, device_ts_ms,
                                             state, temperature, vibration, rpm, alarm_code)
            SELECT * FROM unnest(?::text[], ?::text[], ?::bigint[], ?::bigint[],
                                 ?::text[], ?::float8[], ?::float8[], ?::int[], ?::int[])
            ON CONFLICT (device_id) DO UPDATE SET
                seq = EXCLUDED.seq, device_ts_ms = EXCLUDED.device_ts_ms, state = EXCLUDED.state,
                temperature = EXCLUDED.temperature, vibration = EXCLUDED.vibration,
                rpm = EXCLUDED.rpm, alarm_code = EXCLUDED.alarm_code, updated_at = now()
            WHERE c.seq < EXCLUDED.seq
            RETURNING c.device_id, c.factory_id, c.seq, c.device_ts_ms, c.state,
                      c.temperature, c.vibration, c.rpm, c.alarm_code
            """;

    private static final String SELECT_CURRENT = """
            SELECT device_id, factory_id, seq, device_ts_ms, state, temperature, vibration, rpm, alarm_code
            FROM device_current ORDER BY device_id
            """;

    private static final RowMapper<DeviceSnapshot> SNAPSHOT = (rs, i) -> new DeviceSnapshot(
            rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getString(5),
            rs.getDouble(6), rs.getDouble(7), rs.getInt(8), rs.getInt(9));

    private final JdbcTemplate jdbc;

    public JdbcTelemetryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public int insertNew(List<TelemetryRecord> records) {
        if (records.isEmpty()) return 0;
        Integer n = jdbc.execute((Connection con) -> {
            try (PreparedStatement ps = con.prepareStatement(INSERT_NEW)) {
                int i = 1;
                ps.setArray(i++, array(con, "text", records, TelemetryRecord::deviceId));
                ps.setArray(i++, array(con, "int8", records, TelemetryRecord::seq));
                ps.setArray(i++, array(con, "text", records, TelemetryRecord::factoryId));
                ps.setArray(i++, array(con, "text", records, r -> r.protocol().name()));
                ps.setArray(i++, array(con, "int8", records, TelemetryRecord::deviceTs));
                ps.setArray(i++, array(con, "int8", records, TelemetryRecord::gatewayTs));
                ps.setArray(i++, array(con, "text", records, r -> r.state().name()));
                ps.setArray(i++, array(con, "float8", records, TelemetryRecord::temperature));
                ps.setArray(i++, array(con, "float8", records, TelemetryRecord::vibration));
                ps.setArray(i++, array(con, "int4", records, TelemetryRecord::rpm));
                ps.setArray(i, array(con, "int4", records, TelemetryRecord::alarmCode));
                return ps.executeUpdate(); // 마지막 INSERT가 실제로 넣은 행 수
            }
        });
        return n == null ? 0 : n;
    }

    @Override
    public List<DeviceSnapshot> upsertCurrent(List<TelemetryRecord> latest) {
        if (latest.isEmpty()) return List.of();
        return jdbc.query(con -> {
            PreparedStatement ps = con.prepareStatement(UPSERT_CURRENT);
            int i = 1;
            ps.setArray(i++, array(con, "text", latest, TelemetryRecord::deviceId));
            ps.setArray(i++, array(con, "text", latest, TelemetryRecord::factoryId));
            ps.setArray(i++, array(con, "int8", latest, TelemetryRecord::seq));
            ps.setArray(i++, array(con, "int8", latest, TelemetryRecord::deviceTs));
            ps.setArray(i++, array(con, "text", latest, r -> r.state().name()));
            ps.setArray(i++, array(con, "float8", latest, TelemetryRecord::temperature));
            ps.setArray(i++, array(con, "float8", latest, TelemetryRecord::vibration));
            ps.setArray(i++, array(con, "int4", latest, TelemetryRecord::rpm));
            ps.setArray(i, array(con, "int4", latest, TelemetryRecord::alarmCode));
            return ps;
        }, SNAPSHOT);
    }

    @Override
    public List<DeviceSnapshot> findAllCurrent() {
        return jdbc.query(SELECT_CURRENT, SNAPSHOT);
    }

    @Override
    public void quarantine(String gatewayId, List<RejectedRecord> rejected) {
        // 드물게 생기는 일이라 행마다 넣는다 (일괄 처리할 만큼 많지 않다)
        jdbc.batchUpdate("INSERT INTO ingest_rejected (gateway_id, reason, payload) VALUES (?, ?, ?)",
                rejected, rejected.size(), (ps, r) -> {
                    ps.setString(1, gatewayId);
                    ps.setString(2, r.reason());
                    ps.setString(3, r.payload());
                });
    }

    /** 중복 판정 기록은 2일만 둔다. 재전송 중복은 최대 버퍼 보관 시간(6시간) 안에서만 생긴다. */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    public void purgeDedup() {
        int n = jdbc.update("DELETE FROM telemetry_dedup WHERE received_at < now() - INTERVAL '2 days'");
        if (n > 0) log.info("purged {} dedup keys", n);
    }

    private static <T> Array array(Connection con, String type, List<TelemetryRecord> rows,
                                   Function<TelemetryRecord, T> column) throws SQLException {
        Object[] values = new Object[rows.size()];
        for (int i = 0; i < values.length; i++) values[i] = column.apply(rows.get(i));
        return con.createArrayOf(type, values);
    }
}
