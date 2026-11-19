package fem.server.application;

import fem.common.wire.WireMessages.GatewayStatus;
import fem.server.application.port.out.LiveUpdates;
import fem.server.domain.RemoteCommand;
import fem.server.domain.DeviceSnapshot;
import fem.server.domain.GatewayView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GatewayPresenceServiceTest {

    /** 테스트가 움직이는 시계 */
    private final long[] now = {1_000};

    private final GatewayPresenceService presence = new GatewayPresenceService(new LiveUpdates() {
        @Override
        public void devicesChanged(List<DeviceSnapshot> devices) {
        }

        @Override
        public void gatewayChanged(GatewayView gateway) {
        }

        @Override
        public void commandChanged(RemoteCommand command) {
        }
    }, () -> now[0]);

    private GatewayView gwA() {
        return presence.all().getFirst();
    }

    @Test
    void backlog_is_fresh_only_after_a_status_report_on_the_current_connection() {
        presence.connected("gw-a");
        assertFalse(gwA().statusFresh(), "connected but no report yet");

        presence.status("gw-a", new GatewayStatus("gw-a", 92, 50, 600, 0, 1_000));
        assertTrue(gwA().statusFresh());
        assertEquals(92, gwA().bufferedCount());

        presence.disconnected("gw-a");
        assertFalse(gwA().statusFresh(), "while cut off the real backlog keeps growing");
        assertEquals(92, gwA().bufferedCount(), "last known value is kept, but marked as not current");

        presence.connected("gw-a");
        assertFalse(gwA().statusFresh(), "reconnected: still the value from before the cut");
    }

    @Test
    void two_gateways_with_the_same_token_are_flagged_by_their_reconnect_rate() {
        // 같은 토큰의 두 게이트웨이가 서로 밀어내면 초당 1회 넘게 다시 붙는다 (실측 1분 71회)
        for (int i = 0; i < 5; i++) {
            presence.connected("gw-a");
            presence.disconnected("gw-a");
            now[0] += 1_000;
        }
        assertEquals(5, gwA().reconnectsLastMinute());
        assertTrue(gwA().duplicateSuspected());

        // 한쪽을 멈추면 1분 뒤 경고가 사라진다 (상태 보고가 올 때 지난 기록을 버린다)
        now[0] += 61_000;
        presence.status("gw-a", new GatewayStatus("gw-a", 0, 0, 600, 0, now[0]));
        assertEquals(0, gwA().reconnectsLastMinute());
        assertFalse(gwA().duplicateSuspected());
    }

    @Test
    void ordinary_reconnects_after_a_link_cut_are_not_flagged() {
        // 회선 장애 때 재연결은 백오프(0.5~30초) 간격이라 1분에 몇 번 안 된다
        for (int i = 0; i < 3; i++) {
            presence.connected("gw-a");
            presence.disconnected("gw-a");
            now[0] += 15_000;
        }
        assertFalse(gwA().duplicateSuspected());
    }
}
