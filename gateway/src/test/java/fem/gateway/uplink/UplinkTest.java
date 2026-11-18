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

/** 실제 SQLite outbox + 가짜 연결로 전송·ack·재전송 규칙을 확인한다. 전송 스레드 없이 pumpOnce를 직접 부른다. */
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
        // 묶음 크기 3, 동시에 2묶음까지, ack 시간 초과 10초
        uplink = new Uplink("gw-a", outbox, codec, commands::add, clock::get, 3, 2, 10_000);
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

    @Test
    void sends_nothing_while_disconnected() throws Exception {
        append(1, 5);

        assertFalse(uplink.pumpOnce());
    }

    @Test
    void sends_batches_up_to_window_then_waits_for_ack() throws Exception {
        append(1, 10);
        FakeConnection c = new FakeConnection();
        uplink.onConnected(c);

        assertTrue(uplink.pumpOnce());
        assertTrue(uplink.pumpOnce());
        assertFalse(uplink.pumpOnce(), "window of 2 is full");

        List<Batch> sent = batches(c);
        assertEquals(List.of(1L, 2L, 3L), seqs(sent.get(0)));
        assertEquals(List.of(4L, 5L, 6L), seqs(sent.get(1)));
        assertEquals("gw-a", sent.get(0).gatewayId());
        assertEquals(sent.get(0).toOutboxId() + 1, sent.get(1).fromOutboxId());
    }

    @Test
    void ack_deletes_from_outbox_and_frees_window() throws Exception {
        append(1, 10);
        FakeConnection c = new FakeConnection();
        uplink.onConnected(c);
        uplink.pumpOnce();
        uplink.pumpOnce();
        Batch first = batches(c).getFirst();

        uplink.onText(codec.encode(new Ack(first.toOutboxId())));

        assertEquals(7, outbox.stats().count());
        assertTrue(uplink.pumpOnce());
        assertEquals(List.of(7L, 8L, 9L), seqs(batches(c).get(2)));
    }

    @Test
    void reconnect_resends_everything_after_last_ack() throws Exception {
        append(1, 6);
        FakeConnection c1 = new FakeConnection();
        uplink.onConnected(c1);
        uplink.pumpOnce();
        uplink.pumpOnce();
        uplink.onText(codec.encode(new Ack(batches(c1).get(0).toOutboxId()))); // 첫 묶음만 ack

        uplink.onDisconnected(c1); // 두 번째 묶음 ack 전에 끊김
        FakeConnection c2 = new FakeConnection();
        uplink.onConnected(c2);
        uplink.pumpOnce();

        assertEquals(List.of(4L, 5L, 6L), seqs(batches(c2).getFirst()), "unacked batch is sent again");
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
    void ack_beyond_sent_id_is_ignored() throws Exception {
        append(1, 6);
        FakeConnection c = new FakeConnection();
        uplink.onConnected(c);
        uplink.pumpOnce(); // 1~3만 보냄

        uplink.onText(codec.encode(new Ack(batches(c).getFirst().toOutboxId() + 3)));

        assertEquals(6, outbox.stats().count(), "nothing deleted");
        assertEquals(0, uplink.lastAckedId());
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
        GatewayStatus status = new GatewayStatus("gw-a", 0, 0, 0, 0);
        assertFalse(uplink.sendIfConnected(status));

        FakeConnection c = new FakeConnection();
        uplink.onConnected(c);
        assertTrue(uplink.sendIfConnected(status));
        assertEquals(status, codec.decode(c.sent.getFirst()));
    }
}
