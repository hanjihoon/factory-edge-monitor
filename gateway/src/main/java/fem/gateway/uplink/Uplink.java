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
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * outbox → 서버 전송과 ack 처리. 게이트웨이 신뢰성의 중심이다.
 *
 * <h2>두 줄 전송: 실시간 우선 + 백필</h2>
 * 회선이 끊겼다 돌아오면 outbox에 밀린 데이터가 수십만 건 있다. 이걸 오래된 순서대로만 보내면 지금 측정된
 * 데이터가 그 뒤에 줄을 서서, 관제 화면이 수 분 동안 과거를 보여 주고 새 알람도 늦게 뜬다.
 * 그래서 연결될 때마다 outbox를 두 구간으로 나눈다.
 * <pre>
 *   outbox id:  [ ... 연결 순간까지 쌓인 것 (≤ backfillEnd) ... ] [ 연결 뒤 들어온 것 (> backfillEnd) ... ]
 *                 └ 백필 줄: 남는 전송 슬롯으로 오래된 순서대로          └ 실시간 줄: 항상 먼저 보낸다
 * </pre>
 * <ul>
 *   <li>실시간 줄은 서버에 동시에 한 묶음만 보내 둔다. ack가 오는 동안 새로 쌓인 것을 다음 묶음에 모아 보내므로
 *       묶음 크기가 부하에 맞춰 저절로 커진다 (작은 묶음을 쏟아 내 서버를 바쁘게 하지 않는다).</li>
 *   <li>백필 줄은 나머지 슬롯(window − 1)을 쓴다. 실시간 줄 몫의 슬롯이 항상 하나 남는다.</li>
 *   <li>서버는 한 연결의 묶음을 받은 순서대로 처리하므로, 새 데이터는 앞서 보낸 백필 묶음(최대 window − 1개)
 *       뒤에서만 기다린다. 적체 전체(수십만 건) 뒤에서 기다리던 것보다 훨씬 짧다.</li>
 * </ul>
 *
 * <h2>지워도 되는 것: 범위 ack</h2>
 * 두 줄이 섞여 나가므로 "X번까지 전부 저장"이라는 누적 ack는 쓸 수 없다 (실시간 묶음의 ack로 X 이하를
 * 지우면 아직 안 보낸 백필이 사라진다). 서버는 저장한 묶음의 범위(from~to)를 돌려주고, 게이트웨이는
 * <b>자기가 보낸 묶음과 범위가 정확히 같을 때만</b> 그 범위를 지운다. 그래서 outbox에 남은 행이 곧
 * "서버 저장이 확인되지 않은 행"이고, 재연결·재시작 시 별도 기록 없이 남은 것을 다시 나눠 보내면 된다.
 *
 * <h2>그 밖의 규칙</h2>
 * <ol>
 *   <li>연결이 바뀌면 보낸 기록(inFlight)을 버리고 구간을 다시 나눈다. ack 못 받은 것은 outbox에 남아 있으므로
 *       다시 나간다. 서버는 (deviceId, seq)로 중복을 버린다.</li>
 *   <li>가장 오래된 미확인 묶음이 ackTimeout을 넘으면 연결을 끊는다 → 재연결 → 1번.
 *       (회선이 반쯤 죽어 TCP는 살아 있는데 응답이 안 오는 경우 대비)</li>
 *   <li>순서가 섞여 도착해도 서버의 설비 현재 상태는 seq가 클 때만 갱신되므로, 늦게 도착한 백필이 최신 값을 덮지 않는다.</li>
 * </ol>
 *
 * <h2>스레드</h2>
 * 전송은 {@link #run}을 도는 전송 스레드 하나만 한다. ack·명령 수신은 WebSocket 수신 스레드에서 들어온다.
 * 공유 상태는 {@code this}로 잠그고, 디스크 I/O와 네트워크 전송은 잠금 밖에서 한다 (느린 전송이 ack 처리를 막지 않게).
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

    /** 보냈지만 ack를 받지 못한 묶음. live = 실시간 줄인가 */
    private record InFlight(long fromId, long toId, long sentAt, boolean live) {
    }

    // ---- 아래는 this로 잠근다 ----
    private Connection conn;
    /** 이 id 이하는 백필 줄, 초과는 실시간 줄 (연결 순간의 outbox 최대 id) */
    private long backfillEnd;
    /** 백필 줄에서 마지막으로 보낸 id */
    private long backfillCursor;
    /** 백필 구간을 다 보냈는가 (ack까지 끝났다는 뜻은 아니다) */
    private boolean backfillSent;
    /** 실시간 줄에서 마지막으로 보낸 id */
    private long liveCursor;
    /** 보낸 순서대로 들어 있다 → 맨 앞이 가장 오래된 미확인 묶음 */
    private final Deque<InFlight> inFlight = new ArrayDeque<>();

    public Uplink(String gatewayId, SqliteOutbox outbox, WireCodec codec, Consumer<Command> commandHandler,
                  LongSupplier clock, int batchSize, int window, long ackTimeoutMs) {
        if (window < 2) throw new IllegalArgumentException("window must be >= 2 (one slot is reserved for live data)");
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
        long boundary;
        try {
            boundary = outbox.maxId();
        } catch (Exception e) {
            // 경계를 모르면 전부 백필로 본다 = 예전처럼 오래된 순서대로만 보낸다. 느릴 뿐 유실은 없다.
            log.error("outbox max id read failed, sending everything in order: {}", e.toString());
            boundary = Long.MAX_VALUE;
        }
        synchronized (this) {
            conn = c;
            inFlight.clear();
            backfillEnd = boundary;
            backfillCursor = 0;  // outbox에 남은 가장 오래된 것부터 (지워진 범위는 이미 서버에 저장된 것)
            backfillSent = false;
            liveCursor = boundary == Long.MAX_VALUE ? Long.MAX_VALUE : boundary;
        }
        log.info("uplink connected: backlog up to outbox id {} goes as backfill, newer data first", boundary);
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
            case Ack ack -> onAck(ack.fromOutboxId(), ack.toOutboxId());
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
     * 묶음 하나를 보낼 수 있으면 보낸다. 실시간 줄을 먼저 보고, 보낼 것이 없거나 이미 하나 나가 있으면 백필 줄을 본다.
     *
     * @return 보냈으면 true (곧바로 다음 묶음을 시도할 수 있음)
     */
    boolean pumpOnce() {
        Connection c;
        boolean liveFree;
        boolean backfillFree;
        long liveAfter;
        long backfillAfter;
        long backfillUpTo;
        synchronized (this) {
            if (conn == null || inFlight.size() >= window) return false;
            c = conn;
            long liveOut = inFlight.stream().filter(InFlight::live).count();
            liveFree = liveOut == 0;
            backfillFree = !backfillSent && (inFlight.size() - liveOut) < window - 1;
            liveAfter = liveCursor;
            backfillAfter = backfillCursor;
            backfillUpTo = backfillEnd;
        }

        try {
            if (liveFree) {
                List<SqliteOutbox.Entry> entries = outbox.fetchAfter(liveAfter, batchSize);
                if (!entries.isEmpty()) return send(c, entries, true);
            }
            if (backfillFree) {
                List<SqliteOutbox.Entry> entries = outbox.fetchRange(backfillAfter, backfillUpTo, batchSize);
                if (!entries.isEmpty()) return send(c, entries, false);
                synchronized (this) {
                    if (conn == c && !backfillSent) {
                        backfillSent = true;
                        log.info("backfill up to outbox id {} sent, waiting for remaining acks", backfillUpTo);
                    }
                }
            }
        } catch (Exception e) {
            log.error("outbox read failed: {}", e.toString());
        }
        return false;
    }

    private boolean send(Connection c, List<SqliteOutbox.Entry> entries, boolean live) {
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
            // 보내는 사이에 연결이 바뀌었으면 기록하지 않는다. 새 연결에서 구간을 다시 나눠 보낸다.
            if (conn != c) return false;
            if (live) liveCursor = to;
            else backfillCursor = to;
            inFlight.addLast(new InFlight(from, to, clock.getAsLong(), live));
        }
        return true;
    }

    void onAck(long fromId, long toId) {
        synchronized (this) {
            if (!removeInFlight(fromId, toId)) {
                // 보낸 적 없는 범위(서버 버그)나 끊긴 연결의 늦은 ack. 지우지 않는다.
                // 남겨 두면 다시 보낼 뿐이고(서버가 중복 제거), 잘못 지우면 유실이다.
                log.warn("ack {}..{} does not match any batch in flight, ignored", fromId, toId);
                return;
            }
        }
        try {
            outbox.deleteRange(fromId, toId);
        } catch (Exception e) {
            // 지우기 실패는 유실이 아니다. 행이 남아 있으므로 다음 연결에서 다시 보내고 서버가 중복을 버린다.
            log.warn("outbox delete {}..{} failed: {}", fromId, toId, e.toString());
        }
        wake();
    }

    /** 범위가 정확히 같은 미확인 묶음을 찾아 뺀다. 잠금 안에서 부른다 */
    private boolean removeInFlight(long fromId, long toId) {
        for (Iterator<InFlight> it = inFlight.iterator(); it.hasNext(); ) {
            InFlight f = it.next();
            if (f.fromId() == fromId && f.toId() == toId) {
                it.remove();
                return true;
            }
        }
        return false;
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
