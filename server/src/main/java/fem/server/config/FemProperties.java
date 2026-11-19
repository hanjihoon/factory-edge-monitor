package fem.server.config;

import fem.server.domain.UserSession;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * application.yml의 {@code fem.*} 설정.
 *
 * @param gateways 게이트웨이 ID → 자격 증명. 설치 시 발급된 토큰이라고 가정한다 (설계 가정 6)
 * @param users    관제 사용자 이름 → 계정 (설계 결정 13: 고정 계정 2개)
 */
@ConfigurationProperties("fem")
public record FemProperties(Map<String, GatewayCredential> gateways, Map<String, UserAccount> users) {

    /**
     * @param token   게이트웨이가 핸드셰이크 때 보내는 비밀 값
     * @param factory 이 게이트웨이가 맡은 공장. 다른 공장 설비 데이터는 받지 않는다
     */
    public record GatewayCredential(String token, String factory) {
    }

    /** 과제 범위라 비밀번호를 설정값으로 둔다. 운영에서는 해시 저장 + 사내 계정 연동 (설계 문서 12장) */
    public record UserAccount(String password, UserSession.Role role) {
    }

    public FemProperties {
        gateways = gateways == null ? Map.of() : Map.copyOf(gateways);
        users = users == null ? Map.of() : Map.copyOf(users);
    }
}
