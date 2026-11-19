package fem.server.adapter.in.web;

import fem.server.application.FakeTransactionManager;
import fem.server.application.InMemoryCommandRepository;
import fem.server.application.InMemoryTelemetryRepository;
import fem.server.application.port.out.CommandRepository;
import fem.server.application.port.out.TelemetryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 실제 HTTP·WebSocket으로 로그인, 권한, 명령 접수, 화면 첫 스냅샷을 확인한다. DB는 메모리 구현. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration,"
                + "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration,"
                + "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
        "fem.users.operator.password=op-pw", "fem.users.operator.role=OPERATOR",
        "fem.users.viewer.password=view-pw", "fem.users.viewer.role=VIEWER"
})
class ApiEndToEndTest {

    @TestConfiguration
    static class NoDatabase {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new FakeTransactionManager();
        }
    }

    /** KPI는 DB 연속 집계라 이 테스트(DB 없음)에서는 빈 결과로 대신한다 */
    @TestBean
    fem.server.application.port.out.KpiRepository kpiRepository;

    static fem.server.application.port.out.KpiRepository kpiRepository() {
        return new fem.server.application.port.out.KpiRepository() {
            public java.util.List<fem.server.domain.KpiReport.FactoryKpi> byFactory(java.time.Instant f, java.time.Instant t) { return java.util.List.of(); }
            public java.util.List<fem.server.domain.KpiReport.HourPoint> hourly(java.time.Instant f, java.time.Instant t) { return java.util.List.of(); }
            public java.util.List<fem.server.domain.KpiReport.DeviceKpi> worstDevices(java.time.Instant f, java.time.Instant t, int n) { return java.util.List.of(); }
        };
    }

    @TestBean
    TelemetryRepository telemetryRepository;
    @TestBean
    CommandRepository commandRepository;

    static TelemetryRepository telemetryRepository() {
        return new InMemoryTelemetryRepository();
    }

    static CommandRepository commandRepository() {
        return new InMemoryCommandRepository();
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    private HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String login(String user, String pw) throws Exception {
        HttpResponse<String> r = call("POST", "/api/login", null, "{\"username\":\"" + user + "\",\"password\":\"" + pw + "\"}");
        assertEquals(200, r.statusCode(), r.body());
        return json.readTree(r.body()).get("token").stringValue();
    }

    @Test
    void api_requires_login() throws Exception {
        assertEquals(401, call("GET", "/api/devices", null, null).statusCode());
        assertEquals(401, call("GET", "/api/devices", "bogus", null).statusCode());
        assertEquals(401, call("POST", "/api/login", null, "{\"username\":\"operator\",\"password\":\"nope\"}").statusCode());
    }

    @Test
    void viewer_can_read_but_cannot_command() throws Exception {
        String token = login("viewer", "view-pw");

        assertEquals(200, call("GET", "/api/devices", token, null).statusCode());
        assertEquals(200, call("GET", "/api/gateways", token, null).statusCode());
        assertEquals(403, call("POST", "/api/commands", token,
                "{\"commandId\":\"v-1\",\"deviceId\":\"A-0001\",\"action\":\"STOP\"}").statusCode());
    }

    @Test
    void operator_command_is_accepted_idempotently_and_audited() throws Exception {
        String token = login("operator", "op-pw");
        String body = "{\"commandId\":\"op-1\",\"deviceId\":\"A-0001\",\"action\":\"STOP\"}";

        HttpResponse<String> first = call("POST", "/api/commands", token, body);
        HttpResponse<String> again = call("POST", "/api/commands", token, body);

        assertEquals(202, first.statusCode(), first.body());
        assertEquals(202, again.statusCode());
        JsonNode cmd = json.readTree(first.body());
        assertEquals("operator", cmd.get("requestedBy").stringValue());
        // 게이트웨이가 연결돼 있지 않으므로 대기 상태로 접수된다
        assertEquals("PENDING", cmd.get("status").stringValue());

        JsonNode audit = json.readTree(call("GET", "/api/commands/op-1/audit", token, null).body());
        assertEquals("REQUESTED", audit.get(0).get("event").stringValue());
        long sameId = 0;
        for (JsonNode c : json.readTree(call("GET", "/api/commands", token, null).body())) {
            if (c.get("commandId").stringValue().equals("op-1")) sameId++;
        }
        assertEquals(1, sameId, "one command despite two requests"); // 다른 테스트와 컨텍스트를 공유하므로 이 ID만 센다
    }

    @Test
    void bad_command_requests_get_clear_status_codes() throws Exception {
        String token = login("operator", "op-pw");

        assertEquals(400, call("POST", "/api/commands", token,
                "{\"commandId\":\"x-1\",\"deviceId\":\"Z-0001\",\"action\":\"STOP\"}").statusCode(), "unknown factory");
        assertEquals(400, call("POST", "/api/commands", token,
                "{\"deviceId\":\"A-0001\",\"action\":\"STOP\"}").statusCode(), "missing commandId");
        call("POST", "/api/commands", token, "{\"commandId\":\"x-2\",\"deviceId\":\"A-0001\",\"action\":\"STOP\"}");
        assertEquals(409, call("POST", "/api/commands", token,
                "{\"commandId\":\"x-2\",\"deviceId\":\"A-0002\",\"action\":\"STOP\"}").statusCode(), "id reused for another request");
    }

    @Test
    void ui_websocket_sends_snapshot_and_rejects_missing_token() throws Exception {
        String token = login("viewer", "view-pw");
        CompletableFuture<String> firstFrame = new CompletableFuture<>();
        WebSocket ws = http.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/ui?token=" + token), new WebSocket.Listener() {
                    private final StringBuilder buf = new StringBuilder();

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        buf.append(data);
                        if (last) firstFrame.complete(buf.toString());
                        webSocket.request(1);
                        return null;
                    }
                }).get(5, TimeUnit.SECONDS);
        try {
            JsonNode snapshot = json.readTree(firstFrame.get(5, TimeUnit.SECONDS));
            assertEquals("snapshot", snapshot.get("type").stringValue());
            assertTrue(snapshot.has("devices") && snapshot.has("gateways") && snapshot.has("commands"));
        } finally {
            ws.abort();
        }

        CompletionException e = assertThrows(CompletionException.class, () -> http.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws/ui"), new WebSocket.Listener() {
                }).join());
        assertEquals(401, assertInstanceOf(WebSocketHandshakeException.class, e.getCause()).getResponse().statusCode());
    }
}
