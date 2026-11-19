package fem.gateway;

import fem.common.wire.WireMessages.GatewayStatus;
import fem.gateway.collect.DeviceActivity;
import fem.gateway.outbox.SqliteOutbox;
import fem.gateway.uplink.Uplink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.LongSupplier;

/**
 * 5초마다 게이트웨이 상태(버퍼 적체량, 활성 설비 수, 버린 건수)를 서버로 보낸다.
 * 끊겨 있으면 보내지 않는다. 서버는 "마지막 보고가 언제였나"로 연결 상태를 판단한다.
 */
public final class StatusReporter implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(StatusReporter.class);
    public static final long PERIOD_MS = 5000;

    private final String gatewayId;
    private final SqliteOutbox outbox;
    private final DeviceActivity activity;
    private final Uplink uplink;
    private final LongSupplier clock;

    public StatusReporter(String gatewayId, SqliteOutbox outbox, DeviceActivity activity, Uplink uplink, LongSupplier clock) {
        this.gatewayId = gatewayId;
        this.outbox = outbox;
        this.activity = activity;
        this.uplink = uplink;
        this.clock = clock;
    }

    GatewayStatus snapshot() throws Exception {
        long now = clock.getAsLong();
        SqliteOutbox.Stats s = outbox.stats();
        // now를 먼저 잡고 stats를 읽으므로, 그 사이에 들어온 행이 가장 오래된 행이면 나이가 음수가 된다 (실측 -697ms).
        // 방금 들어온 행이라는 뜻이므로 0으로 본다.
        long oldestAge = s.count() == 0 ? 0 : Math.max(0, now - s.oldestCreatedAt());
        return new GatewayStatus(gatewayId, s.count(), oldestAge, activity.activeWithin(PERIOD_MS, now), s.droppedTotal(), now);
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    GatewayStatus status = snapshot();
                    uplink.sendIfConnected(status);
                    log.info("buffered={} oldestAge={}ms activeDevices={} dropped={} connected={}",
                            status.bufferedCount(), status.oldestBufferedAgeMs(), status.connectedDevices(),
                            status.droppedCount(), uplink.connected());
                } catch (Exception e) {
                    log.warn("status report failed: {}", e.toString());
                }
                Thread.sleep(PERIOD_MS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
