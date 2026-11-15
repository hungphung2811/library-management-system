package lms.security;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Danh tính người gọi trích từ JWT. departmentId phục vụ phân quyền theo phạm vi phòng ban
 * (department-scoped authorization) khi nghiệp vụ cần lọc dữ liệu theo đơn vị. tenantId là tenant của token
 * (null với token không mang claim tenantId, ví dụ người vận hành nền tảng).
 */
public record CurrentUser(UUID userId, String username, UUID departmentId, UUID tenantId) {

    /** Dạng cũ, không có tenant. */
    public CurrentUser(UUID userId, String username, UUID departmentId) {
        this(userId, username, departmentId, null);
    }

    public static CurrentUser from(Jwt jwt) {
        return new CurrentUser(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString(JwtClaims.USERNAME),
                uuidClaim(jwt, JwtClaims.DEPARTMENT_ID), uuidClaim(jwt, JwtClaims.TENANT_ID));
    }

    private static UUID uuidClaim(Jwt jwt, String name) {
        String value = jwt.getClaimAsString(name);
        return value == null ? null : UUID.fromString(value);
    }

    public boolean inDepartment(UUID otherDepartmentId) {
        return departmentId != null && departmentId.equals(otherDepartmentId);
    }
}
