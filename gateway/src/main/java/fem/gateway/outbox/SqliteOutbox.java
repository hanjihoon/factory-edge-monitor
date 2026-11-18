package fem.gateway.outbox;

import fem.common.TelemetryRecord;
import fem.gateway.wire.WireCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * 게이트웨이 디스크 버퍼. 수집한 레코드는 서버 ack를 받기 전까지 여기에 남는다.
 *
 * <h2>유실 0의 근거</h2>
 * <ol>
 *   <li>수집 → {@link #append}가 커밋된 뒤에야 "받았다"고 본다 (MQTT는 이때 브로커에 PUBACK).</li>
 *   <li>서버는 DB 커밋 후 누적 ack(toOutboxId)를 보낸다 → {@link #deleteUpTo}로 그 이하만 지운다.</li>
 *   <li>ack 전에 끊기면 지우지 않았으므로 다시 보낸다. 서버는 (deviceId, seq)로 중복을 버린다.</li>
 * </ol>
 *
 * <h2>id 규칙</h2>
 * {@code AUTOINCREMENT}라 한 번 쓴 id는 행을 지워도 재사용되지 않는다. (없으면 SQLite가 최대값+1을 써서
 * 모두 지운 뒤 재시작하면 id가 되돌아갈 수 있다 → 이미 받은 ack 번호와 겹친다.)
 * 삭제는 항상 앞쪽(작은 id)부터 하므로 남은 행의 id는 빈틈 없이 이어진다.
 *
 * <p>스레드 안전: 연결 하나를 공유하므로 공개 메서드는 모두 synchronized.
 */
public final class SqliteOutbox implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SqliteOutbox.class);

    public record Entry(long id, TelemetryRecord record) {
    }

    /**
     * @param count            미전송(ack 전) 레코드 수
     * @param oldestCreatedAt  가장 오래된 레코드를 쓴 시각(ms). 비어 있으면 0
     * @param droppedTotal     용량 초과로 버린 누적 건수 (이 프로세스 기동 이후)
     */
    public record Stats(long count, long oldestCreatedAt, long droppedTotal) {
    }

    private final Connection conn;
    private final long maxRows;
    private final WireCodec codec;
    private final LongSupplier clock;
    private long count;
    private long droppedTotal;

    /**
     * @param maxRows 상한. 넘으면 가장 오래된 것부터 버린다 (설계 가정 7: 6시간 분량)
     */
    public SqliteOutbox(Path file, long maxRows, WireCodec codec, LongSupplier clock) throws SQLException {
        this.maxRows = maxRows;
        this.codec = codec;
        this.clock = clock;
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement st = conn.createStatement()) {
            // WAL: 쓰는 동안에도 읽기가 막히지 않는다. FULL: 커밋이 끝나면 전원이 나가도 남는다.
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=FULL");
            st.execute("""
                    CREATE TABLE IF NOT EXISTS outbox (
                        id         INTEGER PRIMARY KEY AUTOINCREMENT,
                        created_at INTEGER NOT NULL,
                        payload    TEXT    NOT NULL
                    )""");
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM outbox")) {
                rs.next();
                count = rs.getLong(1); // 시작할 때 한 번만 센다. 이후로는 메모리에서 더하고 뺀다
            }
        }
        conn.setAutoCommit(false);
        log.info("outbox opened at {} with {} pending records", file, count);
    }

    /** 한 트랜잭션으로 쓴다. 반환되면 디스크에 커밋된 것이다. */
    public synchronized void append(List<TelemetryRecord> records) throws SQLException {
        if (records.isEmpty()) return;
        long now = clock.getAsLong();
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO outbox (created_at, payload) VALUES (?, ?)")) {
            for (TelemetryRecord r : records) {
                ps.setLong(1, now);
                ps.setString(2, codec.toJson(r));
                ps.addBatch();
            }
            ps.executeBatch();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        }
        count += records.size();
        enforceCapacity();
    }

    /** afterId보다 큰 id를 오래된 순서로 최대 limit개 */
    public synchronized List<Entry> fetchAfter(long afterId, int limit) throws SQLException {
        List<Entry> out = new ArrayList<>(Math.min(limit, 1024));
        try (PreparedStatement ps = conn.prepareStatement("SELECT id, payload FROM outbox WHERE id > ? ORDER BY id LIMIT ?")) {
            ps.setLong(1, afterId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Entry(rs.getLong(1), codec.fromJson(rs.getString(2), TelemetryRecord.class)));
                }
            }
        }
        conn.commit(); // 읽기 트랜잭션을 닫아야 WAL 체크포인트가 막히지 않는다
        return out;
    }

    /** 서버 ack: id 이하 삭제. 지운 건수 반환 */
    public synchronized int deleteUpTo(long id) throws SQLException {
        int deleted;
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM outbox WHERE id <= ?")) {
            ps.setLong(1, id);
            deleted = ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        }
        count -= deleted;
        return deleted;
    }

    public synchronized Stats stats() throws SQLException {
        long oldest = 0;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT created_at FROM outbox ORDER BY id LIMIT 1")) {
            if (rs.next()) oldest = rs.getLong(1);
        }
        conn.commit();
        return new Stats(count, oldest, droppedTotal);
    }

    /**
     * 상한을 넘으면 가장 오래된 것부터 버린다. 디스크가 가득 차 새 데이터까지 못 쓰게 되는 것보다
     * 오래된 데이터를 버리고 그 건수를 알리는 편이 낫다고 판단했다 (설계 가정 7).
     */
    private void enforceCapacity() throws SQLException {
        long excess = count - maxRows;
        if (excess <= 0) return;
        int deleted;
        // id가 빈틈 없이 이어지므로 "가장 작은 id + excess 미만"이 정확히 가장 오래된 excess개다
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM outbox WHERE id < (SELECT MIN(id) FROM outbox) + ?")) {
            ps.setLong(1, excess);
            deleted = ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        }
        count -= deleted;
        droppedTotal += deleted;
        log.error("outbox over capacity ({}): dropped {} oldest records, total dropped {}", maxRows, deleted, droppedTotal);
    }

    @Override
    public synchronized void close() throws SQLException {
        conn.close();
    }
}
