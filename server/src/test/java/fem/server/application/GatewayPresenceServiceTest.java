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
    }, () -> 1_000);

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
}
