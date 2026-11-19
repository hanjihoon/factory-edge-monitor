package fem.server.application;

import fem.server.config.FemProperties;
import fem.server.domain.GatewayIdentity;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

/** 게이트웨이 토큰 확인. */
@Service
public class GatewayAuthService {

    private final FemProperties properties;

    public GatewayAuthService(FemProperties properties) {
        this.properties = properties;
    }

    public Optional<GatewayIdentity> authenticate(String gatewayId, String token) {
        if (gatewayId == null || token == null) return Optional.empty();
        FemProperties.GatewayCredential cred = properties.gateways().get(gatewayId);
        if (cred == null) return Optional.empty();
        // 문자열 equals는 앞에서부터 다른 글자가 나오면 바로 멈춘다 → 응답 시간으로 토큰을 한 글자씩 추측할 수 있다.
        // MessageDigest.isEqual은 길이가 같으면 항상 끝까지 비교한다 (상수 시간 비교).
        boolean ok = MessageDigest.isEqual(cred.token().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
        return ok ? Optional.of(new GatewayIdentity(gatewayId, cred.factory())) : Optional.empty();
    }
}
