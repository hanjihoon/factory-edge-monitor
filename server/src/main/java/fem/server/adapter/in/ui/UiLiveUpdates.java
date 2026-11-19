package fem.server.adapter.in.ui;

import fem.server.adapter.in.web.ApiController.CommandView;
import fem.server.application.port.out.LiveUpdates;
import fem.server.domain.DeviceSnapshot;
import fem.server.domain.GatewayView;
import fem.server.domain.RemoteCommand;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 화면 실시간 갱신 ({@link LiveUpdates} 구현).
 *
 * <p>설비 변경은 초당 1,000건이다. 하나씩 보내면 브라우저가 매번 다시 그리느라 버티지 못하므로
 * 0.25초 동안 모았다가 설비당 최신 값만 한 번에 보낸다 (지연 최대 0.25초 추가, 목표 p95 2초 안).
 * 게이트웨이·명령 변경은 드물고 즉시 보여야 하므로 바로 보낸다.
 *
 * <p>프레임 형식 (설계 문서 7장):
 * {@code {"type":"devices","items":[...],"serverTime":ms}}, {@code {"type":"gateway","item":{...}}},
 * {@code {"type":"command","item":{...}}}, 접속 직후 {@code {"type":"snapshot",...}}
 */
@Component
public class UiLiveUpdates implements LiveUpdates {

    static final long FLUSH_MS = 250;

    private final UiSessions sessions;
    private final JsonMapper json;
    private final LongSupplier clock;
    /** 아직 안 보낸 설비 변경. 설비 ID → 최신 상태 */
    private Map<String, DeviceSnapshot> pending = new HashMap<>();

    @Autowired // 생성자가 둘이라 Spring에 쓸 것을 알려 준다 (다른 하나는 테스트용 시계 주입)
    public UiLiveUpdates(UiSessions sessions, JsonMapper json) {
        this(sessions, json, System::currentTimeMillis);
    }

    UiLiveUpdates(UiSessions sessions, JsonMapper json, LongSupplier clock) {
        this.sessions = sessions;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void devicesChanged(List<DeviceSnapshot> devices) {
        synchronized (this) {
            for (DeviceSnapshot d : devices) {
                pending.merge(d.deviceId(), d, (a, b) -> b.seq() > a.seq() ? b : a);
            }
        }
    }

    @Override
    public void gatewayChanged(GatewayView gateway) {
        sessions.broadcast(json.writeValueAsString(Map.<String, Object>of("type", "gateway", "item", gateway)));
    }

    @Override
    public void commandChanged(RemoteCommand command) {
        sessions.broadcast(json.writeValueAsString(Map.<String, Object>of("type", "command", "item", CommandView.of(command))));
    }

    @Scheduled(fixedRate = FLUSH_MS)
    public void flush() {
        Map<String, DeviceSnapshot> batch;
        synchronized (this) {
            if (pending.isEmpty()) return;
            batch = pending;
            pending = new HashMap<>(); // 바꿔 끼우고 잠금 밖에서 보낸다
        }
        if (sessions.count() == 0) return;
        // serverTime: 화면이 "측정 → 화면 도착" 지연을 계산할 때 서버 시계 기준으로 쓴다 (7단계 측정)
        sessions.broadcast(json.writeValueAsString(Map.<String, Object>of(
                "type", "devices", "items", batch.values(), "serverTime", clock.getAsLong())));
    }
}
