package fem.server.adapter.out.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 저장소 테스트 공통: 운영과 같은 TimescaleDB 이미지를 띄우고 Flyway 마이그레이션(V1~V4)을 그대로 적용한다.
 *
 * <p>왜 필요한가: 중복 제거·"seq가 클 때만 현재 상태 갱신"은 Java가 아니라 SQL이 지킨다.
 * 서비스 테스트는 메모리 가짜 저장소로 돌기 때문에, SQL을 잘못 고쳐도 빌드가 통과했다.
 *
 * <ul>
 *   <li>컨테이너는 테스트 클래스 전체에서 한 번만 띄운다 (기동 수 초). 테스트마다 표를 비운다
 *   <li>Docker가 없는 PC에서는 실패 대신 건너뛴다 (assumeTrue). 건너뛰면 테스트 보고서에 skipped로 남는다
 *   <li>Spring 컨텍스트 없이 DataSource만 만든다. 무엇이 연결되는지 코드에 다 보이게 하려고
 * </ul>
 */
abstract class TimescaleDbSupport {

    /** docker-compose.yml과 같은 이미지. 버전을 바꾸면 둘 다 바꾼다 */
    private static final DockerImageName IMAGE = DockerImageName.parse("timescale/timescaledb:2.32.0-pg17")
            .asCompatibleSubstituteFor("postgres");

    private static PostgreSQLContainer db;
    protected static DataSource dataSource;
    protected static JdbcTemplate jdbc;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker가 없어 저장소 테스트를 건너뛴다");
        if (db != null) return; // 앞선 테스트 클래스가 이미 띄웠다
        db = new PostgreSQLContainer(IMAGE);
        db.start(); // JVM이 끝나면 Testcontainers가 컨테이너를 정리한다
        dataSource = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE telemetry, telemetry_dedup, device_current, ingest_rejected, command, command_audit");
    }

    protected long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
