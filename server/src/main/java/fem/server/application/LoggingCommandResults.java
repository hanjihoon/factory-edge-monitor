package fem.server.application;

import fem.common.wire.WireMessages.CommandResult;
import fem.server.application.port.in.CommandResultUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 임시 구현: 원격 명령(5단계)이 생기기 전까지 결과를 로그로만 남긴다. */
@Service
public class LoggingCommandResults implements CommandResultUseCase {

    private static final Logger log = LoggerFactory.getLogger(LoggingCommandResults.class);

    @Override
    public void onResult(String gatewayId, CommandResult result) {
        log.info("{} command result {}", gatewayId, result);
    }
}
