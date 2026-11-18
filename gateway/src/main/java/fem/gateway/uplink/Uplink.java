package fem.gateway.uplink;

import fem.common.TelemetryRecord;
import fem.common.wire.WireMessages.Ack;
import fem.common.wire.WireMessages.Batch;
import fem.common.wire.WireMessages.Command;
import fem.gateway.outbox.SqliteOutbox;
import fem.gateway.wire.WireCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * outbox → 서버 전송과 ack 처리. 게이트웨이 신뢰성의 중심이다.
 *
 * <h2>동작</h2>
 * <ol>
 *   <li>outbox에서 lastSentId 다음부터 묶음(최대 batchSize건)을 꺼내 BATCH로 보낸다.</li>
 *   <li>ack를 기다리지 않고 최대 window개까지 연달아 보낸다 (복구 시 적체를 빨리 비우기 위해).</li>
 *   <li>ACK(toOutboxId)가 오면 그 이하를 outbox에서 지운다. 서버는 DB 커밋 뒤에만 ack를 보낸다.</li>
 *   <li>연결이 바뀌면 lastSentId를 lastAckedId로 되돌려 ack 못 받은 것부터 다시 보낸다.</li>
 *   <li>가장 오래된 미확인 묶음이 ackTimeout을 넘으면 연결을 끊는다 → 재연결 → 4번.
 *       (회선이 반쯤 죽어 TCP는 살아 있는데 응답이 안 오는 경우 대비)</li>
 * </ol>
 *
 * <h2>스레드</h2>
 * 전송은 {@link #run}을 도는 전송 스레드 하나만 한다. ack·명령 수신은 WebSocket 수신 스레드에서 들어온다.
 * 공유 상태는 {@code this}로 잠그고, 네트워크 전송은 잠금 밖에서 한다 (느린 전송이 ack 처리를 막지 않게).
 */
public final class Uplink implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(Uplink.class);

    private final String gatewayId;
    private final SqliteOutbox outbox;
    private final WireCodec codec;
    private final Consumer<Command> commandHandler;
    private final LongSupplier clock;
    private final int batchSize;
    private final int window;
    private final long ackTimeoutMs;

    /** 전송 스레드를 깨우는 신호 (새 데이터, ack, 연결) */
    private final Semaphore wakeUp = new Semaphore(0);

    private record InFlight(long toId, long sentAt) {
    }

    // ---- 아래는 this로 잠근다 ----
    private Connection conn;
    private long lastSentId;
    private long lastAckedId;
    private final Deque<InFlight> inFlight = new ArrayDeque<>();

    public Uplink(String gatewayId, SqliteOutbox outbox, WireCodec codec, Consumer<Command> commandHandler,
                  LongSupplier clock, int batchSize, int window, long ackTimeoutMs) {
        this.gatewayId = gatewayId;
        this.outbox = outbox;
        this.codec = codec;
        this.commandHandler = commandHandler;
        this.clock = clock;
        this.batchSize = batchSize;
        this.window = window;
        this.ackTimeoutMs = ackTimeoutMs;
    }

    // ===== 연결기(WebSocketConnector)가 부르는 메서드 =====

    public void onConnected(Connection c) {
        synchronized (this) {
            conn = c;
            lastSentId = lastAckedId; // ack 못 받은 것부터 다시
            inFlight.clear();
        }
        log.info("uplink connected, resend after outbox id {}", lastAckedId);
        wake();
    }

    public void onDisconnected(Connection c) {
        synchronized (this) {
            if (conn != c) return; // 이미 새 연결로 바뀌었으면 무시
            conn = null;
            inFlight.clear();
        }
        log.warn("uplink disconnected");
    }

    /** 서버에서 온 텍스트 프레임 */
    public void onText(String text) {
        Object msg;
        try {
            msg = codec.decode(text);
        } catch (IllegalArgumentException e) {
            log.warn("ignore malformed frame from server: {}", e.getMessage());
            return;
        }
        switch (msg) {
            case Ack ack -> onAck(ack.toOutboxId());
            case Command cmd -> commandHandler.accept(cmd);
            default -> log.warn("unexpected message from server: {}", msg.getClass().getSimpleName());
        }
    }

    // ===== 다른 구성 요소가 부르는 메서드 =====

    /** 새 데이터가 outbox에 들어왔음을 알린다 (IngestWriter 콜백) */
    public void wake() {
        wakeUp.release();
    }

    /**
     * 상태 보고·명령 결과처럼 outbox를 거치지 않는 메시지. 연결이 없으면 보내지 않는다.
     * (상태는 5초 뒤 다시 보내고, 명령 결과는 서버가 재시도하면 다시 만들어지므로 버려도 된다.)
     */
    public boolean sendIfConnected(Object message) {
        Connection c;
        synchronized (this) {
            c = conn;
        }
        if (c == null) return false;
        try {
            c.send(codec.encode(message));
            return true;
        } catch (Exception e) {
            log.warn("send {} failed: {}", message.getClass().getSimpleName(), e.toString());
            c.close("send failed");
            return false;
        }
    }

    public synchronized boolean connected() {
        return conn != null;
    }

    public synchronized long lastAckedId() {
        return lastAckedId;
    }

    // ===== 전송 스레드 =====

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                boolean sent = pumpOnce();
                checkAckTimeout();
                if (!sent) {
                    wakeUp.tryAcquire(200, TimeUnit.MILLISECONDS);
                    wakeUp.drainPermits(); // 쌓인 신호는 한 번에 처리
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 묶음 하나를 보낼 수 있으면 보낸다.
     *
     * @return 보냈으면 true (곧바로 다음 묶음을 시도할 수 있음)
     */
    boolean pumpOnce() {
        Connection c;
        long after;
        synchronized (this) {
            if (conn == null || inFlight.size() >= window) return false;
            c = conn;
            after = lastSentId;
        }

        List<SqliteOutbox.Entry> entries;
        try {
            entries = outbox.fetchAfter(after, batchSize);
        } catch (Exception e) {
            log.error("outbox read failed: {}", e.toString());
            return false;
        }
        if (entries.isEmpty()) return false;

        long from = entries.getFirst().id();
        long to = entries.getLast().id();
        List<TelemetryRecord> records = entries.stream().map(SqliteOutbox.Entry::record).toList();
        try {
            c.send(codec.encode(new Batch(gatewayId, from, to, records)));
        } catch (Exception e) {
            log.warn("batch send failed: {}", e.toString());
            c.close("send failed");
            return false;
        }

        synchronized (this) {
            // 보내는 사이에 연결이 바뀌었으면 기록하지 않는다. 새 연결에서 lastAckedId부터 다시 보낸다.
            if (conn != c) return false;
            lastSentId = to;
            inFlight.addLast(new InFlight(to, clock.getAsLong()));
        }
        return true;
    }

    void onAck(long toId) {
        synchronized (this) {
            if (toId <= lastAckedId) return; // 늦게 온 옛 ack
            if (toId > lastSentId) {
                // 보낸 적 없는 번호까지 지우면 아직 안 보낸 데이터가 사라진다. 서버 버그 방어.
                log.error("ack {} beyond last sent id {}, ignored", toId, lastSentId);
                return;
            }
            lastAckedId = toId;
            while (!inFlight.isEmpty() && inFlight.peekFirst().toId() <= toId) {
                inFlight.pollFirst();
            }
        }
        try {
            outbox.deleteUpTo(toId);
        } catch (Exception e) {
            // 지우기 실패는 유실이 아니다. 다음 ack 때 함께 지워지고, 그전에 재시작하면 중복 전송될 뿐이다.
            log.warn("outbox delete up to {} failed: {}", toId, e.toString());
        }
        wake();
    }

    void checkAckTimeout() {
        Connection c;
        synchronized (this) {
            InFlight oldest = inFlight.peekFirst();
            if (conn == null || oldest == null || clock.getAsLong() - oldest.sentAt() < ackTimeoutMs) return;
            c = conn;
        }
        log.warn("no ack within {}ms, reconnecting", ackTimeoutMs);
        c.close("ack timeout");
    }
}
