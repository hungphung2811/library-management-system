package lms.identity.auth.application;

import java.util.List;
import java.util.UUID;

public interface TokenIssuerPort {

    IssuedToken issue(Claims claims);

    /**
     * tenantId: tenant của phiên (null với phiên nền tảng không tenant); platformAdmin: tài khoản có cờ platform_admin;
     * modules: module đang bật của tenant (claim {@code modules}, ADR-0009), null = không ghi claim (phiên nền tảng);
     * audience: giá trị claim {@code aud} (ví dụ {@code lms-storefront} cho kênh storefront), null = để issuer quyết
     * (kênh nhân viên); sessionId: họ phiên, ghi claim {@code sid} (ADR-0017), null = không ghi.
     */
    record Claims(UUID userId, String username, UUID departmentId, List<String> roles, List<String> permissions,
                  boolean mustChangePassword, boolean admin, UUID tenantId, boolean platformAdmin,
                  List<String> modules, String audience, UUID sessionId) {

        public Claims(UUID userId, String username, UUID departmentId, List<String> roles, List<String> permissions,
                      boolean mustChangePassword, boolean admin, UUID tenantId, boolean platformAdmin,
                      List<String> modules, String audience) {
            this(userId, username, departmentId, roles, permissions, mustChangePassword, admin, tenantId,
                    platformAdmin, modules, audience, null);
        }

        /** Không audience: token kênh nhân viên (không ghi aud, hoặc aud=lms-staff nếu issuer được cấu hình). */
        public Claims(UUID userId, String username, UUID departmentId, List<String> roles, List<String> permissions,
                      boolean mustChangePassword, boolean admin, UUID tenantId, boolean platformAdmin,
                      List<String> modules) {
            this(userId, username, departmentId, roles, permissions, mustChangePassword, admin, tenantId,
                    platformAdmin, modules, null);
        }

        public Claims(UUID userId, String username, UUID departmentId, List<String> roles, List<String> permissions,
                      boolean mustChangePassword, boolean admin, UUID tenantId, boolean platformAdmin) {
            this(userId, username, departmentId, roles, permissions, mustChangePassword, admin, tenantId,
                    platformAdmin, null);
        }

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
