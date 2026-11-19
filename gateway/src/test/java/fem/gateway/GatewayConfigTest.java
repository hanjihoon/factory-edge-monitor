package fem.gateway;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GatewayConfigTest {

    private static Map<String, String> base() {
        Map<String, String> env = new HashMap<>();
        env.put("GATEWAY_ID", "gw-a");
        env.put("FACTORY_ID", "A");
        env.put("GATEWAY_TOKEN", "secret-token");
        return env;
    }

    @Test
    void defaults_follow_design_assumptions() {
        GatewayConfig c = GatewayConfig.from(base());

        assertEquals("mqtt", c.source());
        assertEquals(600L * 21_600, c.bufferMaxRows(), "6 hours of 600 devices");
        assertEquals(500, c.modbusPollMs());
    }

    @Test
    void missing_required_values_fail_fast() {
        Map<String, String> env = base();
        env.remove("GATEWAY_TOKEN");

        assertThrows(IllegalArgumentException.class, () -> GatewayConfig.from(env));
    }

    @Test
    void rejects_unknown_source() {
        Map<String, String> env = base();
        env.put("SOURCE", "opcua");

        assertThrows(IllegalArgumentException.class, () -> GatewayConfig.from(env));
    }

    @Test
    void token_is_not_printed() {
        assertFalse(GatewayConfig.from(base()).toString().contains("secret-token"));
    }
}
