package fem.server.adapter.in.web;

import fem.common.wire.WireMessages.CommandAction;
import fem.server.application.GatewayPresenceService;
import fem.server.application.UserAuthService;
import fem.server.application.port.in.IssueCommandUseCase;
import fem.server.application.port.out.TelemetryRepository;
import fem.server.domain.AuditEntry;
import fem.server.domain.DeviceSnapshot;
import fem.server.domain.GatewayView;
import fem.server.domain.UserSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 관제 UI용 REST API. 명세는 설계 문서 7장.
 * 실시간 변경은 UI WebSocket(/ws/ui)으로 받고, 이 API는 첫 화면 로딩·명령 요청·감사 로그 조회에 쓴다.
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final UserAuthService auth;
    private final TelemetryRepository telemetry;
    private final GatewayPresenceService gateways;
    private final IssueCommandUseCase commands;

    public ApiController(UserAuthService auth, TelemetryRepository telemetry,
                         GatewayPresenceService gateways, IssueCommandUseCase commands) {
        this.auth = auth;
        this.telemetry = telemetry;
        this.gateways = gateways;
        this.commands = commands;
    }

    public record LoginRequest(String username, String password) {
    }

    public record LoginResponse(String token, String username, UserSession.Role role, long expiresAt) {
    }

    public record CommandRequest(String commandId, String deviceId, CommandAction action) {
    }

    /** 명령 응답. 도메인 객체를 그대로 내보내지 않고 화면에 필요한 필드만 */
    public record CommandView(String commandId, String deviceId, String gatewayId, CommandAction action,
                              String requestedBy, long createdAt, String status, int attempts,
                              long completedAt, String outcome, String detail) {
        public static CommandView of(fem.server.domain.RemoteCommand c) {
            return new CommandView(c.commandId(), c.deviceId(), c.gatewayId(), c.action(), c.requestedBy(),
                    c.createdAt(), c.status().name(), c.attempts(), c.completedAt(),
                    c.outcome() == null ? null : c.outcome().name(), c.detail());
        }
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest req) {
        UserSession s = auth.login(req.username(), req.password())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials"));
        return new LoginResponse(s.token(), s.username(), s.role(), s.expiresAt());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        auth.logout(AuthInterceptor.bearer(authorization));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestAttribute(AuthInterceptor.USER) UserSession user) {
        return Map.of("username", user.username(), "role", user.role());
    }

    @GetMapping("/devices")
    public List<DeviceSnapshot> devices() {
        return telemetry.findAllCurrent();
    }

    @GetMapping("/gateways")
    public List<GatewayView> gateways() {
        return gateways.all();
    }

    @GetMapping("/commands")
    public List<CommandView> recentCommands(@RequestParam(defaultValue = "50") int limit) {
        return commands.recent(Math.min(Math.max(limit, 1), 500)).stream().map(CommandView::of).toList();
    }

    @GetMapping("/commands/{commandId}/audit")
    public List<AuditEntry> audit(@PathVariable String commandId) {
        return commands.audit(commandId);
    }

    /**
     * 명령 요청. 운영자만 가능. 결과는 비동기라 202(접수됨)를 돌려준다.
     * 같은 commandId로 다시 보내면 새 명령을 만들지 않고 기존 명령을 돌려준다.
     */
    @PostMapping("/commands")
    public ResponseEntity<CommandView> issue(@RequestAttribute(AuthInterceptor.USER) UserSession user,
                                             @RequestBody CommandRequest req) {
        if (!user.role().canCommand()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "viewer cannot issue commands");
        }
        if (req.commandId() == null || req.commandId().isBlank() || req.commandId().length() > 64
                || req.deviceId() == null || req.action() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "commandId(1~64자), deviceId, action are required");
        }
        try {
            var c = commands.issue(req.commandId(), req.deviceId(), req.action(), user.username());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(CommandView.of(c));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }
}
