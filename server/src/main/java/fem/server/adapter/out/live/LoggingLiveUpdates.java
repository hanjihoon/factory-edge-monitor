package fem.server.adapter.out.live;

import fem.server.application.port.out.LiveUpdates;
import fem.server.domain.DeviceSnapshot;
import fem.server.domain.GatewayView;
import fem.server.domain.RemoteCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/** 임시 구현: UI WebSocket(6단계)이 생기기 전까지 변경 사항을 로그로만 남긴다. */
@Component
public class LoggingLiveUpdates implements LiveUpdates {

    private static final Logger log = LoggerFactory.getLogger(LoggingLiveUpdates.class);

    @Override
    public void devicesChanged(List<DeviceSnapshot> devices) {
        log.debug("{} devices changed", devices.size());
    }

    @Override
    public void gatewayChanged(GatewayView gateway) {
        log.info("gateway {}", gateway);
    }

    @Override
    public void commandChanged(RemoteCommand command) {
        log.info("command {} {} {} → {} (attempt {})", command.commandId(), command.action(), command.deviceId(),
                command.status(), command.attempts());
    }
}
