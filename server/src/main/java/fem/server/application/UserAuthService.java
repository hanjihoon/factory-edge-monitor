package fem.server.application;

import fem.server.config.FemProperties;
import fem.server.domain.UserSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 관제 사용자 로그인과 토큰 확인 (설계 결정 13: 고정 계정 2개).
 *
 * <p>토큰은 추측할 수 없는 무작위 값(32바이트)이고 서버 메모리에만 있다. 서버를 재시작하면 다시 로그인해야 한다.
 * JWT를 쓰지 않은 이유: 서버 1대라 상태를 서버에 두는 편이 더 단순하고, 로그아웃·만료를 즉시 반영할 수 있다.
 */
@Service
public class UserAuthService {

    static final long TOKEN_TTL_MS = 8 * 60 * 60 * 1000L; // 근무 교대 한 번

    private final FemProperties properties;
    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, UserSession> sessions = new ConcurrentHashMap<>();

    @Autowired
    public UserAuthService(FemProperties properties) {
        this(properties, System::currentTimeMillis);
    }

    UserAuthService(FemProperties properties, LongSupplier clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public Optional<UserSession> login(String username, String password) {
        if (username == null || password == null) return Optional.empty();
        FemProperties.UserAccount account = properties.users().get(username);
        if (account == null) return Optional.empty();
        boolean ok = MessageDigest.isEqual(account.password().getBytes(StandardCharsets.UTF_8),
                password.getBytes(StandardCharsets.UTF_8));
        if (!ok) return Optional.empty();

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UserSession session = new UserSession(token, username, account.role(), clock.getAsLong() + TOKEN_TTL_MS);
        sessions.put(token, session);
        return Optional.of(session);
    }

    public Optional<UserSession> verify(String token) {
        if (token == null) return Optional.empty();
        UserSession s = sessions.get(token);
        if (s == null) return Optional.empty();
        if (clock.getAsLong() >= s.expiresAt()) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(s);
    }

    public void logout(String token) {
        if (token != null) sessions.remove(token);
    }
}
