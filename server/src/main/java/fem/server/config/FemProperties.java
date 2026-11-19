package fem.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * application.yml의 {@code fem.*} 설정.
 *
 * @param gateways 게이트웨이 ID → 자격 증명. 설치 시 발급된 토큰이라고 가정한다 (설계 가정 6)
 */
@ConfigurationProperties("fem")
public record FemProperties(Map<String, GatewayCredential> gateways) {

    /**
     * @param token   게이트웨이가 핸드셰이크 때 보내는 비밀 값
     * @param factory 이 게이트웨이가 맡은 공장. 다른 공장 설비 데이터는 받지 않는다
     */
    public record GatewayCredential(String token, String factory) {
    }

    public FemProperties {
        gateways = gateways == null ? Map.of() : Map.copyOf(gateways);
    }
}
