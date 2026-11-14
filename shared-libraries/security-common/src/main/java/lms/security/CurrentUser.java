package lms.security;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Danh tính người gọi trích từ JWT. departmentId phục vụ phân quyền theo phạm vi phòng ban
 * (department-scoped authorization) khi nghiệp vụ cần lọc dữ liệu theo đơn vị.
 */
public record CurrentUser(UUID userId, String username, UUID departmentId) {

    public static CurrentUser from(Jwt jwt) {
        String department = jwt.getClaimAsString(JwtClaims.DEPARTMENT_ID);
        return new CurrentUser(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString(JwtClaims.USERNAME),
                department == null ? null : UUID.fromString(department));
    }

    public boolean inDepartment(UUID otherDepartmentId) {
        return departmentId != null && departmentId.equals(otherDepartmentId);
    }
}
