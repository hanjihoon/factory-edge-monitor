package fem.server.adapter.in.gateway;

import fem.common.wire.WireMessages.Ack;
import fem.common.wire.WireMessages.Batch;
import fem.common.wire.WireMessages.CommandResult;
import fem.common.wire.WireMessages.GatewayStatus;
import fem.server.application.GatewayPresenceService;
import fem.server.application.port.in.CommandResultUseCase;
import fem.server.application.port.in.IngestBatchUseCase;
import fem.server.domain.GatewayIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 게이트웨이 메시지 한 건을 알맞은 유스케이스로 보내고, 돌려보낼 응답이 있으면 만든다.
 * WebSocket API와 분리해 두어 세션 없이 테스트할 수 있다.
 */
@Component
public class GatewayMessageRouter {

    private static final Logger log = LoggerFactory.getLogger(GatewayMessageRouter.class);

    private final IngestBatchUseCase ingest;
    private final GatewayPresenceService presence;
    private final CommandResultUseCase commandResults;

    public GatewayMessageRouter(IngestBatchUseCase ingest, GatewayPresenceService presence, CommandResultUseCase commandResults) {
        this.ingest = ingest;
        this.presence = presence;
        this.commandResults = commandResults;
    }

    /**
     * @return 게이트웨이에 보낼 응답 (BATCH → ACK)
     * @throws RuntimeException 저장 실패. 호출자는 ack 없이 연결을 끊어 게이트웨이가 재전송하게 한다
     */
    public Optional<Object> route(GatewayIdentity gw, Object message) {
        return switch (message) {
            case Batch batch -> {
                // body의 gatewayId는 믿지 않는다. 인증된 신원(gw)을 기준으로 한다.
                IngestBatchUseCase.Result r = ingest.ingest(gw.gatewayId(), gw.factoryId(), batch);
                if (r.duplicates() > 0 || r.rejected() > 0) {
                    log.info("{} batch {}..{}: inserted={} duplicates={} rejected={}", gw.gatewayId(),
                            batch.fromOutboxId(), batch.toOutboxId(), r.inserted(), r.duplicates(), r.rejected());
                }
                yield Optional.of(new Ack(batch.toOutboxId())); // 커밋이 끝난 뒤에만 여기 온다
            }
            case GatewayStatus status -> {
                presence.status(gw.gatewayId(), status);
                yield Optional.empty();
            }
            case CommandResult result -> {
                commandResults.onResult(gw.gatewayId(), result);
                yield Optional.empty();
            }
            default -> {
                log.warn("{} sent unexpected {}", gw.gatewayId(), message.getClass().getSimpleName());
                yield Optional.empty();
            }
        };
    }
}
