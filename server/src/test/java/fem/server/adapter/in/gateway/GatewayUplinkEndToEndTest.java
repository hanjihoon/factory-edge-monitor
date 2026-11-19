package fem.server.adapter.in.gateway;

import fem.common.DeviceState;
import fem.common.Protocol;
import fem.common.TelemetryRecord;
import fem.gateway.outbox.SqliteOutbox;
import fem.gateway.uplink.Backoff;
import fem.gateway.uplink.Uplink;
import fem.gateway.uplink.WebSocketConnector;
import fem.gateway.wire.WireCodec;
import fem.server.application.FakeTransactionManager;
import fem.server.application.GatewayPresenceService;
import fem.server.application.InMemoryTelemetryRepository;
import fem.server.application.port.out.CommandRepository;
import fem.server.application.port.out.TelemetryRepository;
import fem.server.application.InMemoryCommandRepository;
import fem.server.domain.GatewayView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.PlatformTransactionManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실제 게이트웨이 전송 코드(SqliteOutbox → Uplink → WebSocketConnector)를 실제 서버 WebSocket에 붙인다.
 * DB만 메모리 구현으로 바꾼다 (로컬에 TimescaleDB가 없어도 프로토콜·인증·ack·재전송을 검증하기 위해).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
        "fem.gateways.gw-a.token=test-token",
        "fem.gateways.gw-a.factory=A"
})
class GatewayUplinkEndToEndTest {

    @TestConfiguration
    static class NoDatabase {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new FakeTransactionManager();
        }
    }

    /** JDBC 구현 대신 메모리 구현을 쓴다 (같은 중복 제거 규칙) */
    @TestBean
    TelemetryRepository repository;

    @TestBean
    CommandRepository commandRepository;

    static CommandRepository commandRepository() {
        return new InMemoryCommandRepository();
    }

    static TelemetryRepository repository() {
        return new InMemoryTelemetryRepository();
    }

    @LocalServerPort
    int port;
    @Autowired
    GatewayPresenceService presence;
    @TempDir
    Path dir;

    private final List<Thread> threads = new ArrayList<>();
    private final WireCodec codec = new WireCodec();

    @AfterEach
    void stop() {
        threads.forEach(Thread::interrupt);
    }

    private InMemoryTelemetryRepository store() {
        return (InMemoryTelemetryRepository) repository;
    }

    /** 스프링 컨텍스트(메모리 저장소)를 테스트끼리 공유하므로 설비별로 센다 */
    private long storedOf(String deviceId) {
        return store().stored.stream().filter(r -> r.deviceId().equals(deviceId)).count();
    }

    private static List<TelemetryRecord> records(String device, long from, long to) {
        return LongStream.rangeClosed(from, to)
                .mapToObj(s -> new TelemetryRecord(device, "A", Protocol.MQTT, s, s, s, DeviceState.RUNNING, 60, 2, 1500, 0))
                .toList();
    }

    private Uplink startGateway(SqliteOutbox outbox, String token) {
        return startGateway(outbox, token, cmd -> {});
    }

    private Uplink startGateway(SqliteOutbox outbox, String token, java.util.function.Consumer<fem.common.wire.WireMessages.Command> commands) {
        Uplink uplink = new Uplink("gw-a", outbox, codec, commands, System::currentTimeMillis, 500, 8, 5_000);
        WebSocketConnector connector = new WebSocketConnector(URI.create("ws://127.0.0.1:" + port + "/ws/gateway"),
                "gw-a", token, uplink, new Backoff(100, 500, new Random(1)));
        threads.add(Thread.ofVirtual().start(uplink));
        threads.add(Thread.ofVirtual().start(connector));
        return uplink;
    }

    private static void await(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("condition not met within " + timeoutMs + "ms");
            Thread.sleep(20);
        }
    }

    @Test
    void buffered_records_reach_server_and_are_acked_away() throws Exception {
        try (SqliteOutbox outbox = new SqliteOutbox(dir.resolve("a.db"), 1_000_000, codec, System::currentTimeMillis)) {
            outbox.append(records("A-0001", 1, 1200));
            outbox.append(records("A-0002", 1, 300));

            startGateway(outbox, "test-token");

            await(() -> {
                try {
                    return outbox.stats().count() == 0;
                } catch (Exception e) {
                    return false;
                }
            }, 10_000);
            assertEquals(1200, storedOf("A-0001"), "every record stored exactly once");
            assertEquals(300, storedOf("A-0002"));
            assertEquals(1200, store().findAllCurrent().stream().filter(d -> d.deviceId().equals("A-0001")).findFirst().orElseThrow().seq(),
                    "current state is the newest");
            assertTrue(presence.all().stream().anyMatch(GatewayView::connected));
        }
    }

    @Test
    void records_resent_after_lost_acks_are_not_stored_twice() throws Exception {
        // 서버에는 이미 저장됐지만 ack를 못 받은 상황을 만든다: 같은 레코드를 두 outbox에서 보낸다
        try (SqliteOutbox first = new SqliteOutbox(dir.resolve("1.db"), 1_000_000, codec, System::currentTimeMillis)) {
            first.append(records("A-0005", 1, 100));
            startGateway(first, "test-token");
            await(() -> storedOf("A-0005") == 100, 10_000);
        }
        stop();
        threads.clear();

        try (SqliteOutbox resend = new SqliteOutbox(dir.resolve("2.db"), 1_000_000, codec, System::currentTimeMillis)) {
            resend.append(records("A-0005", 51, 150)); // 51~100은 중복, 101~150은 새것
            startGateway(resend, "test-token");
            await(() -> {
                try {
                    return resend.stats().count() == 0;
                } catch (Exception e) {
                    return false;
                }
            }, 10_000);
        }
        assertEquals(150, storedOf("A-0005"));
    }

    @Test
    void wrong_token_is_rejected_at_handshake() {
        HttpClient http = HttpClient.newHttpClient();
        CompletionException e = assertThrows(CompletionException.class, () -> http.newWebSocketBuilder()
                .header("X-Gateway-Id", "gw-a")
                .header("Authorization", "Bearer wrong")
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/gateway"), new WebSocket.Listener() {
                })
                .orTimeout(5, TimeUnit.SECONDS)
                .join());
        var handshake = assertInstanceOf(java.net.http.WebSocketHandshakeException.class, e.getCause());
        assertEquals(401, handshake.getResponse().statusCode());
    }

    @Autowired
    fem.server.application.port.in.IssueCommandUseCase commandUseCase;

    @Test
    void command_round_trip_through_real_gateway_command_service() throws Exception {
        // 게이트웨이 쪽: 실제 CommandService + 설비 대신 즉시 APPLIED를 돌려주는 실행기. 실행 횟수를 센다
        java.util.concurrent.atomic.AtomicInteger executions = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Uplink> uplinkRef = new java.util.concurrent.atomic.AtomicReference<>();
        fem.gateway.command.CommandService gatewayCommands = new fem.gateway.command.CommandService(
                (id, device, action) -> {
                    executions.incrementAndGet();
                    return new fem.gateway.command.CommandExecutor.Result(
                            fem.common.wire.WireMessages.CommandOutcome.APPLIED, "stopped");
                },
                result -> uplinkRef.get().sendIfConnected(result), System::currentTimeMillis);
        try (SqliteOutbox outbox = new SqliteOutbox(dir.resolve("cmd.db"), 1000, codec, System::currentTimeMillis)) {
            Uplink uplink = startGateway(outbox, "test-token", gatewayCommands::handle);
            uplinkRef.set(uplink);
            await(uplink::connected, 5_000);

            commandUseCase.issue("e2e-1", "A-0009", fem.common.wire.WireMessages.CommandAction.STOP, "operator");
            commandUseCase.issue("e2e-1", "A-0009", fem.common.wire.WireMessages.CommandAction.STOP, "operator"); // 더블클릭

            await(() -> commandUseCase.recent(50).stream().anyMatch(c -> c.commandId().equals("e2e-1")
                    && c.status() == fem.server.domain.RemoteCommand.Status.SUCCEEDED), 5_000);
            assertEquals(1, executions.get(), "applied exactly once");
            assertTrue(commandUseCase.audit("e2e-1").stream().anyMatch(a -> a.event().equals("RESULT") && a.actor().equals("gw-a")));
        } finally {
            gatewayCommands.close();
        }
    }
}
