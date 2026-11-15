package lms.security;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

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

    /**
     * Claim sub/departmentId/tenantId sai định dạng UUID (hoặc thiếu sub) là token không hợp lệ: ném
     * {@link InvalidBearerTokenException} (AuthenticationException) để ExceptionTranslationFilter trả 401,
     * thông điệp cố định, không lộ giá trị claim.
     */
    public static CurrentUser from(Jwt jwt) {
        UUID userId = uuidClaim(jwt.getSubject());
        if (userId == null) {
            throw new InvalidBearerTokenException("Token không hợp lệ");
        }
        return new CurrentUser(userId, jwt.getClaimAsString(JwtClaims.USERNAME),
                uuidClaim(jwt.getClaim(JwtClaims.DEPARTMENT_ID)), uuidClaim(jwt.getClaim(JwtClaims.TENANT_ID)));
    }

    private static UUID uuidClaim(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value.toString());
        } catch (IllegalArgumentException e) {
            throw new InvalidBearerTokenException("Token không hợp lệ");
        }
    }

    public boolean inDepartment(UUID otherDepartmentId) {
        return departmentId != null && departmentId.equals(otherDepartmentId);
    }
}
