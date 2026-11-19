package fem.server.adapter.in.web;

import fem.server.application.UserAuthService;
import fem.server.domain.UserSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/**
 * {@code /api/**} 요청마다 {@code Authorization: Bearer <토큰>}을 확인한다 (로그인 제외).
 * 통과하면 사용자 정보를 요청 속성에 넣어 컨트롤러가 꺼내 쓴다.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    static final String USER = "fem.user";

    private final UserAuthService auth;

    public AuthInterceptor(UserAuthService auth) {
        this.auth = auth;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Optional<UserSession> user = auth.verify(bearer(request.getHeader("Authorization")));
        if (user.isEmpty()) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return false;
        }
        request.setAttribute(USER, user.get());
        return true;
    }

    static String bearer(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }
}
