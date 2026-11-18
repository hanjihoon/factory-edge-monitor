package fem.gateway.uplink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * 서버로 WebSocket을 열고, 끊기면 백오프 후 다시 연다. 게이트웨이가 먼저 여는 아웃바운드 연결뿐이다.
 *
 * <p>인증: 연결 요청 헤더에 게이트웨이 토큰을 싣는다 ({@code Authorization: Bearer ...}).
 * 서버는 핸드셰이크 단계에서 거부하므로 인증 안 된 연결로는 메시지가 오가지 않는다.
 */
public final class WebSocketConnector implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConnector.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final long SEND_TIMEOUT_MS = 10_000;

    private final URI uri;
    private final String gatewayId;
    private final String token;
    private final Uplink uplink;
    private final Backoff backoff;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    public WebSocketConnector(URI uri, String gatewayId, String token, Uplink uplink, Backoff backoff) {
        this.uri = uri;
        this.gatewayId = gatewayId;
        this.token = token;
        this.uplink = uplink;
        this.backoff = backoff;
    }

    @Override
    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            WsConnection conn = new WsConnection();
            try {
                WebSocket ws = http.newWebSocketBuilder()
                        .connectTimeout(CONNECT_TIMEOUT)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Gateway-Id", gatewayId)
                        .buildAsync(uri, conn)
                        .get(CONNECT_TIMEOUT.toMillis() * 2, TimeUnit.MILLISECONDS);
                conn.ws = ws;
                backoff.reset();
                uplink.onConnected(conn);
                conn.closed.join(); // 끊길 때까지 여기서 기다린다
                uplink.onDisconnected(conn);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("connect to {} failed: {}", uri, rootMessage(e));
            }
            try {
                long delay = backoff.nextDelayMs();
                log.info("reconnect in {}ms", delay);
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static String rootMessage(Throwable e) {
        while (e.getCause() != null) e = e.getCause();
        return e.toString();
    }

    /** 연결 하나 = 이 객체 하나. 수신 리스너이자 {@link Connection} 구현이다. */
    private final class WsConnection implements Connection, WebSocket.Listener {
        volatile WebSocket ws;
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        /** 긴 메시지는 여러 조각으로 온다. last=true가 올 때까지 이어 붙인다. */
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1); // 한 번에 메시지 하나씩 받겠다는 흐름 제어 신호
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                try {
                    uplink.onText(text);
                } catch (RuntimeException e) {
                    log.warn("handling server message failed: {}", e.toString());
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.warn("server closed connection: {} {}", statusCode, reason);
            closed.complete(null);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("websocket error: {}", error.toString());
            closed.complete(null);
        }

        /**
         * JDK WebSocket은 이전 전송이 끝나기 전에 다시 보내면 IllegalStateException을 던진다.
         * synchronized + 완료 대기로 한 번에 하나씩만 보낸다.
         */
        @Override
        public synchronized void send(String text) throws Exception {
            WebSocket w = ws;
            if (w == null || closed.isDone()) throw new IllegalStateException("not connected");
            w.sendText(text, true).get(SEND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        @Override
        public void close(String reason) {
            log.warn("closing connection: {}", reason);
            WebSocket w = ws;
            if (w != null) w.abort(); // 응답 없는 회선에서 정상 종료 절차를 기다리지 않는다
            closed.complete(null);
        }
    }
}
