package fem.server.application;

import fem.server.config.FemProperties;
import fem.server.domain.GatewayIdentity;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GatewayAuthServiceTest {

    private final GatewayAuthService auth = new GatewayAuthService(new FemProperties(Map.of(
            "gw-a", new FemProperties.GatewayCredential("secret-a", "A"))));

    @Test
    void valid_token_yields_identity_with_factory() {
        assertEquals(Optional.of(new GatewayIdentity("gw-a", "A")), auth.authenticate("gw-a", "secret-a"));
    }

    @Test
    void wrong_token_unknown_gateway_or_missing_values_are_rejected() {
        assertTrue(auth.authenticate("gw-a", "secret-b").isEmpty());
        assertTrue(auth.authenticate("gw-a", "secret-a-longer").isEmpty());
        assertTrue(auth.authenticate("gw-x", "secret-a").isEmpty());
        assertTrue(auth.authenticate(null, "secret-a").isEmpty());
        assertTrue(auth.authenticate("gw-a", null).isEmpty());
    }
}
