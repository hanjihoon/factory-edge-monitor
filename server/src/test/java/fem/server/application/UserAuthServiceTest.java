package fem.server.application;

import fem.server.config.FemProperties;
import fem.server.domain.UserSession;
import fem.server.domain.UserSession.Role;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class UserAuthServiceTest {

    private final AtomicLong clock = new AtomicLong(0);
    private final UserAuthService auth = new UserAuthService(new FemProperties(Map.of(), Map.of(
            "operator", new FemProperties.UserAccount("op-pw", Role.OPERATOR),
            "viewer", new FemProperties.UserAccount("view-pw", Role.VIEWER))), clock::get);

    @Test
    void login_issues_token_that_verifies_to_same_user_and_role() {
        UserSession s = auth.login("viewer", "view-pw").orElseThrow();

        assertEquals(Role.VIEWER, auth.verify(s.token()).orElseThrow().role());
        assertFalse(s.role().canCommand());
        assertTrue(auth.login("operator", "op-pw").orElseThrow().role().canCommand());
    }

    @Test
    void tokens_are_unique_per_login() {
        assertNotEquals(auth.login("viewer", "view-pw").orElseThrow().token(),
                auth.login("viewer", "view-pw").orElseThrow().token());
    }

    @Test
    void wrong_password_or_unknown_user_fails() {
        assertTrue(auth.login("viewer", "op-pw").isEmpty());
        assertTrue(auth.login("admin", "x").isEmpty());
        assertTrue(auth.login(null, null).isEmpty());
        assertTrue(auth.verify("made-up").isEmpty());
    }

    @Test
    void token_expires_after_ttl_and_on_logout() {
        String t1 = auth.login("operator", "op-pw").orElseThrow().token();
        String t2 = auth.login("operator", "op-pw").orElseThrow().token();

        auth.logout(t2);
        assertTrue(auth.verify(t2).isEmpty());

        clock.set(UserAuthService.TOKEN_TTL_MS - 1);
        assertTrue(auth.verify(t1).isPresent());
        clock.set(UserAuthService.TOKEN_TTL_MS);
        assertTrue(auth.verify(t1).isEmpty());
    }
}
