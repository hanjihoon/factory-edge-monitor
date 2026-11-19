package fem.server.application;

import fem.common.wire.WireMessages.GatewayStatus;
import fem.server.application.port.out.LiveUpdates;
import fem.server.domain.GatewayView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/**
 * 게이트웨이 연결 상태와 마지막 상태 보고를 메모리에 들고 있다가 화면에 알린다.
 * 서버 1대 구성이라 메모리로 충분하다 (여러 대로 늘리면 공유 저장소가 필요 → 설계 문서 12장).
 *
 * <p>같은 토큰을 쓰는 게이트웨이가 둘이면 서로 밀어내며 초당 1회 넘게 다시 붙는다 (실측 1분 71회).
 * 그래서 최근 1분 접속 횟수를 세어 화면에 올리고, 기준을 넘으면 ERROR 로그를 한 번 남긴다.
 */
@Service
public class GatewayPresenceService {

    private static final Logger log = LoggerFactory.getLogger(GatewayPresenceService.class);
    private static final long WINDOW_MS = 60_000;

    private final LiveUpdates live;
    private final LongSupplier clock;
    private final Map<String, GatewayView> views = new TreeMap<>();
    /** 게이트웨이별 최근 접속 시각들 (1분 지난 것은 버린다). views와 같은 잠금(this)으로 보호한다 */
    private final Map<String, Deque<Long>> connects = new HashMap<>();

    @Autowired // 생성자가 둘이라 Spring에 어느 쪽을 쓸지 알려 준다 (아래 것은 테스트용 시계 주입)
    public GatewayPresenceService(LiveUpdates live) {
        this(live, System::currentTimeMillis);
    }

    GatewayPresenceService(LiveUpdates live, LongSupplier clock) {
        this.live = live;
        this.clock = clock;
    }

    // 연결·끊김 때는 적체 값을 지우지 않고 statusFresh만 false로 둔다. 마지막으로 알던 값은 남겨 두되,
    // 그것이 "지금 값"이 아니라는 표시를 붙이는 것이다 (끊긴 동안 게이트웨이 적체는 계속 늘어난다).

    public void connected(String gatewayId) {
        long now = clock.getAsLong();
        update(gatewayId, true, (v, reconnects) -> new GatewayView(gatewayId, true, now, v.bufferedCount(),
                v.oldestBufferedAgeMs(), v.connectedDevices(), v.droppedCount(), false, reconnects));
    }

    public void disconnected(String gatewayId) {
        update(gatewayId, false, (v, reconnects) -> new GatewayView(gatewayId, false, v.lastSeenAt(), v.bufferedCount(),
                v.oldestBufferedAgeMs(), v.connectedDevices(), v.droppedCount(), false, reconnects));
    }

    public void status(String gatewayId, GatewayStatus s) {
        long now = clock.getAsLong();
        update(gatewayId, false, (v, reconnects) -> new GatewayView(gatewayId, true, now, s.bufferedCount(),
                s.oldestBufferedAgeMs(), s.connectedDevices(), s.droppedCount(), true, reconnects));
    }

    public synchronized List<GatewayView> all() {
        return List.copyOf(views.values());
    }

    private interface Change {
        GatewayView apply(GatewayView previous, int reconnectsLastMinute);
    }

    /**
     * @param isConnect 새 접속이면 true (접속 횟수에 더한다). 끊김·상태 보고 때도 1분 지난 기록을 버려
     *                  중복 접속이 멈추면 경고가 자연히 사라진다 (상태 보고가 5초마다 오므로)
     */
    private void update(String gatewayId, boolean isConnect, Change change) {
        GatewayView next;
        boolean becameSuspect;
        synchronized (this) {
            long now = clock.getAsLong();
            Deque<Long> times = connects.computeIfAbsent(gatewayId, id -> new ArrayDeque<>());
            if (isConnect) times.addLast(now);
            while (!times.isEmpty() && times.peekFirst() <= now - WINDOW_MS) times.pollFirst();

            GatewayView prev = views.getOrDefault(gatewayId, new GatewayView(gatewayId, false, 0, 0, 0, 0, 0, false, 0));
            next = change.apply(prev, times.size());
            views.put(gatewayId, next);
            becameSuspect = next.duplicateSuspected() && !prev.duplicateSuspected();
        }
        if (becameSuspect) {
            log.error("{} reconnected {} times in the last minute: another gateway may be using the same token. "
                    + "Data is being split across two gateways' disks", gatewayId, next.reconnectsLastMinute());
        }
        live.gatewayChanged(next); // 잠금 밖에서 알린다 (느린 화면 전송이 다른 게이트웨이 처리를 막지 않게)
    }
}
