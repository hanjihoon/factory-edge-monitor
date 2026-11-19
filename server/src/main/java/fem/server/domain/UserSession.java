package fem.server.domain;

/**
 * 로그인한 관제 사용자.
 *
 * @param role OPERATOR(조회 + 명령) 또는 VIEWER(조회만)
 */
public record UserSession(String token, String username, Role role, long expiresAt) {

    public enum Role {
        OPERATOR, VIEWER;

        public boolean canCommand() {
            return this == OPERATOR;
        }
    }
}
