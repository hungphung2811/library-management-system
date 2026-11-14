package lms.identity.auth.application;

import java.util.List;
import java.util.UUID;

public interface TokenIssuerPort {

    IssuedToken issue(Claims claims);

    record Claims(UUID userId, String username, UUID departmentId, List<String> roles, List<String> permissions,
                  boolean mustChangePassword, boolean admin) {

        public Claims(UUID userId, String username, UUID departmentId, List<String> roles, List<String> permissions,
                      boolean mustChangePassword) {
            this(userId, username, departmentId, roles, permissions, mustChangePassword, false);
        }

        public Claims(UUID userId, String username, UUID departmentId, List<String> roles, List<String> permissions) {
            this(userId, username, departmentId, roles, permissions, false, false);
        }
    }

    record IssuedToken(String value, long expiresInSeconds) {
    }
}
