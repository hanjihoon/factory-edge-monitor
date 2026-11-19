package fem.server.application;

import fem.common.wire.WireMessages.GatewayStatus;
import fem.server.application.port.out.LiveUpdates;
import fem.server.domain.GatewayView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * 게이트웨이 연결 상태와 마지막 상태 보고를 메모리에 들고 있다가 화면에 알린다.
 * 서버 1대 구성이라 메모리로 충분하다 (여러 대로 늘리면 공유 저장소가 필요 → 설계 문서 12장).
 */
@Service
public class GatewayPresenceService {

    private final LiveUpdates live;
    private final LongSupplier clock;
    private final Map<String, GatewayView> views = new TreeMap<>();

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
        update(gatewayId, v -> new GatewayView(gatewayId, true, clock.getAsLong(), v.bufferedCount(),
                v.oldestBufferedAgeMs(), v.connectedDevices(), v.droppedCount(), false));
    }

    public void disconnected(String gatewayId) {
        update(gatewayId, v -> new GatewayView(gatewayId, false, v.lastSeenAt(), v.bufferedCount(),
                v.oldestBufferedAgeMs(), v.connectedDevices(), v.droppedCount(), false));
    }

    public void status(String gatewayId, GatewayStatus s) {
        update(gatewayId, v -> new GatewayView(gatewayId, true, clock.getAsLong(), s.bufferedCount(),
                s.oldestBufferedAgeMs(), s.connectedDevices(), s.droppedCount(), true));
    }

    public synchronized List<GatewayView> all() {
        return List.copyOf(views.values());
    }

    private void update(String gatewayId, java.util.function.UnaryOperator<GatewayView> change) {
        GatewayView next;
        synchronized (this) {
            GatewayView prev = views.getOrDefault(gatewayId, new GatewayView(gatewayId, false, 0, 0, 0, 0, 0, false));
            next = change.apply(prev);
            views.put(gatewayId, next);
        }
        live.gatewayChanged(next); // 잠금 밖에서 알린다 (느린 화면 전송이 다른 게이트웨이 처리를 막지 않게)
    }
}
