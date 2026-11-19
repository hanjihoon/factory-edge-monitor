package fem.server.application.port.out;

import fem.server.domain.DeviceSnapshot;
import fem.server.domain.GatewayView;
import fem.server.domain.RemoteCommand;

import java.util.List;

/**
 * 관제 화면으로 실시간 변경을 내보내는 출력 포트. 구현은 UI WebSocket (6단계).
 * 애플리케이션 계층이 WebSocket을 모르게 하려고 포트로 둔다.
 */
public interface LiveUpdates {

    void devicesChanged(List<DeviceSnapshot> devices);

    void gatewayChanged(GatewayView gateway);

    void commandChanged(RemoteCommand command);
}
