package fem.gateway.uplink;

import fem.common.DeviceState;
import fem.common.Protocol;
import fem.common.TelemetryRecord;
import fem.common.wire.WireMessages.Ack;
import fem.common.wire.WireMessages.Batch;
import fem.gateway.outbox.SqliteOutbox;
import fem.gateway.wire.WireCodec;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 속성 기반 테스트: 무작위 사건 순서 수천 가지에서 "유실 0"의 핵심 규칙이 한 번도 깨지지 않는지 본다.
 *
 * <h2>왜 필요한가</h2>
 * {@link UplinkTest}는 사람이 떠올린 순서(끊김 → 재연결 → 재전송 등)만 확인한다. 실제 장애는 순서가 제멋대로다:
 * ack가 늦게 오거나 사라지고, 서버가 묶음을 순서 없이 처리하고, 끊긴 연결로 보낸 묶음이 나중에 저장되고,
 * 그 사이 게이트웨이가 재시작된다. 여기서는 그런 사건을 무작위로 섞은 시나리오를 jqwik이 수천 개 만들고,
 * 실패하면 실패를 재현하는 가장 짧은 시나리오로 줄여서 보여 준다 (shrinking).
 *
 * <h2>확인하는 규칙 (불변식)</h2>
 * <ol>
 *   <li><b>안전:</b> 매 사건 뒤에, outbox에서 지워진 레코드는 반드시 이미 DB에 있다.
 *       (= 게이트웨이는 서버가 저장을 확인한 것만 지운다. 이것이 깨지면 유실이다)
 *   <li><b>완료:</b> 시나리오 끝에 회선을 정상으로 두면, outbox가 비고 지금까지 들어온 레코드가 전부 DB에 있다.
 *   <li><b>중복 저장 없음:</b> 같은 레코드가 여러 번 전달돼도 DB에는 한 번만 (서버 중복 제거를 집합으로 흉내)
 * </ol>
 *
 * <p>가짜 서버는 받은 묶음을 <b>아무 순서로나</b> 처리한다. 서버가 묶음을 병렬로 저장해도 범위 ack 규칙이
 * 안전하다는 근거이기도 하다 (설계 문서 7장).
 */
class UplinkPropertyTest {

    /** 시나리오를 이루는 사건 */
    enum Event {
        DATA,             // 설비 데이터 3건이 outbox에 들어온다
        CONNECT,          // 회선이 붙는다 (이미 붙어 있으면 끊고 새로 붙는 것과 같다)
        DISCONNECT,       // 회선이 끊긴다
        PUMP,             // 게이트웨이 전송 스레드가 한 번 돈다
        SERVER_SAVE_ACK,  // 서버가 받은 묶음 하나(아무거나)를 저장하고 ack를 보낸다. 끊겨 있으면 ack는 사라진다
        SERVER_SAVE_LOST, // 서버가 저장은 했는데 ack가 회선에서 사라진다
        SERVER_FAIL,      // 서버가 묶음 하나를 저장에 실패하고 버린다 (ack 없음)
        ACK_TIMEOUT,      // 15초 넘게 ack가 없어 게이트웨이가 연결을 끊는다
        RESTART           // 게이트웨이 프로세스가 죽었다 다시 뜬다 (메모리 상태는 사라지고 outbox 파일만 남는다)
    }

    @Provide
    Arbitrary<List<Tuple.Tuple2<Event, Integer>>> scenarios() {
        // 사건과 "서버가 몇 번째 묶음을 고를지"를 짝지은 목록. 길이 1~120
        Arbitrary<Event> events = Arbitraries.of(Event.class);
        Arbitrary<Integer> picks = Arbitraries.integers().between(0, 1_000);
        return net.jqwik.api.Combinators.combine(events, picks).as(Tuple::of).list().ofMinSize(1).ofMaxSize(120);
    }

    @Property(tries = 1_000)
    void no_record_is_ever_deleted_before_the_server_stored_it(@ForAll("scenarios") List<Tuple.Tuple2<Event, Integer>> scenario)
            throws Exception {
        Path dir = Files.createTempDirectory("uplink-prop");
        try (World w = new World(dir.resolve("outbox.db"))) {
            for (var step : scenario) {
                w.apply(step.get1(), step.get2());
                w.assertSafety();
            }
            w.drainOnHealthyLink();
            w.assertEverythingStored();
        }
    }

    /** 게이트웨이(진짜 Uplink + 진짜 SQLite outbox)와 가짜 서버·회선을 함께 들고 있는 시험 세계 */
    static final class World implements AutoCloseable {
        private static final long ACK_TIMEOUT_MS = 15_000;

        private final Path file;
        private final WireCodec codec = new WireCodec();
        private final AtomicLong clock = new AtomicLong(1_000);
        private SqliteOutbox outbox;
        private Uplink uplink;
        private UplinkTest.FakeConnection conn;     // 지금 붙어 있는 연결 (없으면 null)
        private int framesSeen;                     // conn.sent 중 서버 수신함으로 옮긴 개수

        private long nextSeq = 1;
        private final Set<Long> appended = new HashSet<>();   // 지금까지 outbox에 들어간 레코드 (seq)
        private final Set<Long> db = new HashSet<>();         // 서버 DB. 집합이라 중복 저장이 생길 수 없다 = 서버 중복 제거
        private final List<Batch> serverInbox = new ArrayList<>(); // 서버가 받았지만 아직 처리하지 않은 묶음

        World(Path file) throws Exception {
            this.file = file;
            open();
        }

        private void open() throws Exception {
            outbox = new SqliteOutbox(file, 1_000_000, codec, clock::get);
            // 묶음 2건, 동시에 3묶음(실시간 1 + 백필 2) — 운영 설정과 같은 창 크기, 묶음은 작게 해서 경우의 수를 늘린다
            uplink = new Uplink("gw-a", outbox, codec, c -> { }, clock::get, 2, 3, ACK_TIMEOUT_MS);
            conn = null;
        }

        void apply(Event e, int pick) throws Exception {
            clock.addAndGet(100);
            switch (e) {
                case DATA -> {
                    List<TelemetryRecord> rs = LongStream.range(nextSeq, nextSeq + 3).mapToObj(World::rec).toList();
                    outbox.append(rs);
                    rs.forEach(r -> appended.add(r.seq()));
                    nextSeq += 3;
                }
                case CONNECT -> {
                    if (conn != null) uplink.onDisconnected(conn);
                    conn = new UplinkTest.FakeConnection();
                    framesSeen = 0;
                    uplink.onConnected(conn);
                }
                case DISCONNECT -> {
                    if (conn != null) uplink.onDisconnected(conn);
                    conn = null;
                }
                case PUMP -> {
                    uplink.pumpOnce();
                    collectSentFrames();
                }
                case SERVER_SAVE_ACK -> serverProcess(pick, true, true);
                case SERVER_SAVE_LOST -> serverProcess(pick, true, false);
                case SERVER_FAIL -> serverProcess(pick, false, false);
                case ACK_TIMEOUT -> {
                    clock.addAndGet(ACK_TIMEOUT_MS + 1);
                    uplink.checkAckTimeout();
                    if (conn != null && conn.closedReason != null) { // 게이트웨이가 스스로 끊었다
                        uplink.onDisconnected(conn);
                        conn = null;
                    }
                }
                case RESTART -> {
                    // 서버 쪽에 이미 도착한 묶음(serverInbox)은 그대로 남는다: 게이트웨이가 죽어도 서버는 처리를 끝낼 수 있다
                    outbox.close();
                    open();
                }
            }
        }

        /** 게이트웨이가 지금 연결로 보낸 프레임 중 묶음을 서버 수신함으로 옮긴다 */
        private void collectSentFrames() {
            if (conn == null) return;
            for (; framesSeen < conn.sent.size(); framesSeen++) {
                if (codec.decode(conn.sent.get(framesSeen)) instanceof Batch b) serverInbox.add(b);
            }
        }

        /**
         * 서버가 수신함에서 아무 묶음 하나를 꺼내 처리한다 (처리 순서는 무작위 = 병렬 저장과 같다).
         * 실제 서버처럼 "저장(커밋) 뒤에만" ack를 보낸다. ack는 게이트웨이 ID로 보내므로 지금 붙어 있는 연결로 간다.
         */
        private void serverProcess(int pick, boolean saved, boolean ackDelivered) {
            if (serverInbox.isEmpty()) return;
            Batch b = serverInbox.remove(pick % serverInbox.size());
            if (!saved) return;
            b.records().forEach(r -> db.add(r.seq()));
            if (ackDelivered && conn != null) {
                uplink.onText(codec.encode(new Ack(b.fromOutboxId(), b.toOutboxId())));
            }
        }

        /** 불변식 1: outbox에 없는(= 지워진) 레코드는 반드시 DB에 있다 */
        void assertSafety() throws Exception {
            Set<Long> inOutbox = outbox.fetchAfter(0, Integer.MAX_VALUE).stream()
                    .map(en -> en.record().seq()).collect(Collectors.toSet());
            for (long seq : appended) {
                if (!inOutbox.contains(seq)) {
                    assertTrue(db.contains(seq), "record " + seq + " was deleted from the outbox but never stored");
                }
            }
        }

        /** 시나리오 끝: 회선을 정상으로 두고 다 보낼 때까지 돌린다 (서버는 받은 순서대로 저장하고 ack) */
        void drainOnHealthyLink() throws Exception {
            apply(Event.CONNECT, 0);
            for (int i = 0; i < 10_000 && outbox.stats().count() > 0; i++) {
                uplink.pumpOnce();
                collectSentFrames();
                while (!serverInbox.isEmpty()) serverProcess(0, true, true);
                assertSafety();
            }
            // 끊긴 연결로 갔다가 처리되지 않고 남은 묶음은 실제로는 사라진 것이다 (서버가 연결 종료와 함께 버림)
            serverInbox.clear();
        }

        /** 불변식 2: 들어온 것은 전부 DB에 있고 outbox는 비었다 */
        void assertEverythingStored() throws Exception {
            assertEquals(0, outbox.stats().count(), "outbox should drain on a healthy link");
            assertEquals(appended, db, "every record that entered the outbox reached the database");
        }

        private static TelemetryRecord rec(long seq) {
            return new TelemetryRecord("A-0001", "A", Protocol.MQTT, seq, seq, seq, DeviceState.RUNNING, 0, 0, 0, 0);
        }

        @Override
        public void close() throws Exception {
            outbox.close();
        }
    }
}
