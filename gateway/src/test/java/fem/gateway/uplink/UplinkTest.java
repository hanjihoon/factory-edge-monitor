package fem.gateway.uplink;

import fem.common.DeviceState;
import fem.common.Protocol;
import fem.common.TelemetryRecord;
import fem.common.wire.WireMessages.*;
import fem.gateway.outbox.SqliteOutbox;
import fem.gateway.wire.WireCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 실제 SQLite outbox + 가짜 연결로 전송·ack·재전송 규칙을 확인한다. 전송 스레드 없이 pumpOnce를 직접 부른다.
 *
 * <p>용어: "밀린 데이터(백필)" = 연결되는 순간 이미 outbox에 있던 것, "실시간" = 연결 뒤에 들어온 것.
 */
class UplinkTest {

    @TempDir
    Path dir;
    private final WireCodec codec = new WireCodec();
    private final AtomicLong clock = new AtomicLong(0);
    private final List<Command> commands = new ArrayList<>();
    private SqliteOutbox outbox;
    private Uplink uplink;

    /** 보낸 프레임을 기록하는 가짜 연결 */
    static final class FakeConnection implements Connection {
        final List<String> sent = new ArrayList<>();
        String closedReason;
        boolean failSends;

        @Override
        public void send(String text) throws Exception {
            if (failSends) throw new java.io.IOException("broken pipe");
            sent.add(text);
        }

        @Override
        public void close(String reason) {
            closedReason = reason;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        outbox = new SqliteOutbox(dir.resolve("b.db"), 1_000_000, codec, clock::get);
        // 묶음 크기 3, 동시에 2묶음까지 (실시간 1 + 백필 1), ack 시간 초과 10초
        uplink = uplink(2);
    }

    private Uplink uplink(int window) {
        return new Uplink("gw-a", outbox, codec, commands::add, clock::get, 3, window, 10_000);
    }

    @AfterEach
    void tearDown() throws Exception {
        outbox.close();
    }

    private void append(long fromSeq, long toSeq) throws Exception {
        outbox.append(LongStream.rangeClosed(fromSeq, toSeq)
                .mapToObj(s -> new TelemetryRecord("A-0001", "A", Protocol.MQTT, s, s, s, DeviceState.RUNNING, 0, 0, 0, 0))
                .toList());
    }

    private List<Batch> batches(FakeConnection c) {
        return c.sent.stream().map(codec::decode).filter(Batch.class::isInstance).map(Batch.class::cast).toList();
    }

    private static List<Long> seqs(Batch b) {
        return b.records().stream().map(TelemetryRecord::seq).toList();
    }

    private List<Long> remainingSeqs() throws Exception {
        return outbox.fetchAfter(0, 1000).stream().map(e -> e.record().seq()).toList();
    }

    private void ack(Batch b) {
        uplink.onText(codec.encode(new Ack(b.fromOutboxId(), b.toOutboxId())));
    }

    @Test
    void sends_nothing_while_disconnected() throws Exception {
        append(1, 5);

        assertFalse(uplink.pumpOnce());
    }

    @Test
    void window_below_two_is_rejected_because_one_slot_is_reserved_for_live_data() {
        assertThrows(IllegalArgumentException.class, () -> uplink(1));
    }

    @Test
    void backlog_is_sent_oldest_first_using_window_minus_one_slots() throws Exception {
        uplink = uplink(3);
        append(1, 10);
        FakeConnection c = new FakeConnection();
        uplink.onConnected(c);

        assertTrue(uplink.pumpOnce());
        assertTrue(uplink.pumpOnce());
        assertFalse(uplink.pumpOnce(), "backfill may use only window - 1 = 2 slots");

        List<Batch> sent = batches(c);
        assertEquals(List.of(1L, 2L, 3L), seqs(sent.get(0)));
        assertEquals(List.of(4L, 5L, 6L), seqs(sent.get(1)));
        assertEquals("gw-a", sent.get(0).gatewayId());
        assertEquals(sent.get(0).toOutboxId() + 1, sent.get(1).fromOutboxId());
    }

    @Test
    void live_data_is_sent_before_backlog() throws Exception {
        append(1, 6);                      // 끊긴 동안 쌓인 것
        FakeConnection c = new FakeConnection();
        uplink.onConnected(c);
        append(7, 8);                      // 연결 뒤 새로 측정된 것

        uplink.pumpOnce();
        uplink.pumpOnce();

        List<Batch> sent = batches(c);
        assertEquals(List.of(7L, 8L), seqs(sent.get(0)), "live first");
        assertEquals(List.of(1L, 2L, 3L), seqs(sent.get(1)), "then backlog in the remaining slot");
        assertFalse(uplink.pumpOnce(), "window of 2 is full");
    }

    @Test
    void only_one_live_batch_is_in_flight_so_new_data_accumulates_into_the_next_batch() throws Exception {
        FakeConnection c = new FakeConnection();
        uplink.onConnected(c);
        append(1, 2);
        assertTrue(uplink.pumpOnce());
        append(3, 3);

        assertFalse(uplink.pumpOnce(), "previous live batch not acked yet");

        ack(batches(c).getFirst());
        append(4, 4);
        assertTrue(uplink.pumpOnce());
        assertEquals(List.of(3L, 4L), seqs(batches(c).get(1)));
    }

    @Test
    void ack_of_live_batch_deletes_only_that_batch_and_keeps_backlog() throws Exception {
        // 누적 ack였다면 7~8의 ack로 그 아래(1~6)까지 지워져 유실이 났을 상황
        append(1, 6);
        FakeConnection c = new FakeConnection();
        uplink.onConnected(c);
        append(7, 8);
        uplink.pumpOnce(); // 실시간 7~8
        uplink.pumpOnce(); // 백필 1~3

        ack(batches(c).get(0));

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), remainingSeqs());
        assertEquals(6, outbox.stats().count());
    }

    @Test
    void ack_frees_a_slot_and_backfill_continues() throws Exception {
        append(1, 9);
        FakeConnection c = new FakeConnection();
        uplink.onConnected(c);
        uplink.pumpOnce(); // 백필 1~3
        assertFalse(uplink.pumpOnce(), "backfill slot (window - 1 = 1) is full");

        ack(batches(c).getFirst());

        assertEquals(List.of(4L, 5L, 6L, 7L, 8L, 9L), remainingSeqs());
        assertTrue(uplink.pumpOnce());
        assertEquals(List.of(4L, 5L, 6L), seqs(batches(c).get(1)));
    }

    @Test
    void reconnect_resends_only_what_was_not_acked() throws Exception {
        append(1, 6);
        FakeConnection c1 = new FakeConnection();
        uplink.onConnected(c1);
        append(7, 8);
        uplink.pumpOnce(); // 실시간 7~8
        uplink.pumpOnce(); // 백필 1~3
        ack(batches(c1).get(0)); // 실시간만 ack, 백필 1~3은 ack 전에 끊김

        uplink.onDisconnected(c1);
        FakeConnection c2 = new FakeConnection();
        uplink.onConnected(c2);
        uplink.pumpOnce();

        assertEquals(List.of(1L, 2L, 3L), seqs(batches(c2).getFirst()), "unacked backlog is sent again");
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), remainingSeqs(), "acked live data is not sent again");
    }

    @Test
    void after_restart_everything_left_in_outbox_is_sent_again() throws Exception {
        append(1, 6);
        FakeConnection c1 = new FakeConnection();
        uplink.onConnected(c1);
        append(7, 8);
        uplink.pumpOnce();
        ack(batches(c1).getFirst()); // 7~8 저장 확인 → outbox에는 1~6만 남는다 (id에 빈틈은 없지만 뒤쪽이 비었다)

        outbox.close();                     // 게이트웨이 재시작
        outbox = new SqliteOutbox(dir.resolve("b.db"), 1_000_000, codec, clock::get);
        uplink = uplink(2);
        FakeConnection c2 = new FakeConnection();
        uplink.onConnected(c2);
        uplink.pumpOnce();
        ack(batches(c2).getFirst());
        uplink.pumpOnce();

        assertEquals(List.of(1L, 2L, 3L), seqs(batches(c2).get(0)));
        assertEquals(List.of(4L, 5L, 6L), seqs(batches(c2).get(1)));
    }

    @Test
    void ack_timeout_closes_connection() throws Exception {
        append(1, 3);
        FakeConnection c = new FakeConnection();
        uplink.onConnected(c);
        uplink.pumpOnce();

        clock.set(9_999);
        uplink.checkAckTimeout();
        assertNull(c.closedReason);

        clock.set(10_000);
        uplink.checkAckTimeout();
        assertEquals("ack timeout", c.closedReason);
    }

    @Test
    void ack_that_does_not_match_a_sent_batch_is_ignored() throws Exception {
        append(1, 6);
        FakeConnection c = new FakeConnection();
        uplink.onConnected(c);
        uplink.pumpOnce(); // 1~3만 보냄
        Batch sent = batches(c).getFirst();

        uplink.onText(codec.encode(new Ack(sent.fromOutboxId(), sent.toOutboxId() + 3))); // 보낸 적 없는 범위
        uplink.onText(codec.encode(new Ack(sent.fromOutboxId() + 1, sent.toOutboxId()))); // 일부만 겹침

        assertEquals(6, outbox.stats().count(), "nothing deleted");
    }

    @Test
    void failed_send_closes_connection_and_does_not_advance() throws Exception {
        append(1, 3);
        FakeConnection broken = new FakeConnection();
        broken.failSends = true;
        uplink.onConnected(broken);

        assertFalse(uplink.pumpOnce());
        assertEquals("send failed", broken.closedReason);

        FakeConnection ok = new FakeConnection();
        uplink.onConnected(ok);
        uplink.pumpOnce();
        assertEquals(List.of(1L, 2L, 3L), seqs(batches(ok).getFirst()));
    }

    @Test
    void stale_disconnect_of_old_connection_is_ignored() throws Exception {
        FakeConnection c1 = new FakeConnection();
        FakeConnection c2 = new FakeConnection();
        uplink.onConnected(c1);
        uplink.onConnected(c2);

        uplink.onDisconnected(c1);

        assertTrue(uplink.connected());
    }

    @Test
    void command_frames_go_to_command_handler() {
        Command cmd = new Command("c-1", "A-0001", CommandAction.STOP, 1, 1);

        uplink.onText(codec.encode(cmd));
        uplink.onText("garbage"); // 무시되고 예외도 없다

        assertEquals(List.of(cmd), commands);
    }

    @Test
    void status_is_sent_only_when_connected() {
        GatewayStatus status = new GatewayStatus("gw-a", 0, 0, 0, 0, 0);
        assertFalse(uplink.sendIfConnected(status));

        FakeConnection c = new FakeConnection();
        uplink.onConnected(c);
        assertTrue(uplink.sendIfConnected(status));
        assertEquals(status, codec.decode(c.sent.getFirst()));
    }
}
