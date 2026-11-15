package lms.identity.auth.application;

import java.util.List;
import java.util.UUID;

public interface TokenIssuerPort {

    IssuedToken issue(Claims claims);

    /**
     * tenantId: tenant của phiên (null với phiên nền tảng không tenant); platformAdmin: tài khoản có cờ platform_admin.
     */
    record Claims(UUID userId, String username, UUID departmentId, List<String> roles, List<String> permissions,
                  boolean mustChangePassword, boolean admin, UUID tenantId, boolean platformAdmin) {

        public Claims(UUID userId, String username, UUID departmentId, List<String> roles, List<String> permissions,
                      boolean mustChangePassword, boolean admin) {
            this(userId, username, departmentId, roles, permissions, mustChangePassword, admin, null, false);
        }

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
