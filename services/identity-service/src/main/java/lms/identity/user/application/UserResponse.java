package lms.identity.user.application;

import lms.identity.user.domain.TenantUser;
import lms.identity.user.domain.User;
import lms.identity.user.domain.UserStatus;
import java.util.UUID;
import lms.tenant.TenantContext;

/**
 * departmentId: phòng ban trong tenant của dòng (tenant_memberships.department_id); tenantId: tenant của dòng
 * (ở phạm vi đọc ALL của platform admin mỗi dòng là một cặp người dùng - tenant, ADR-0008).
 */
public record UserResponse(UUID id, String username, String fullName, String email, UUID departmentId, UserStatus status,
                           UUID tenantId) {

    public UserResponse(UUID id, String username, String fullName, String email, UUID departmentId, UserStatus status) {
        this(id, username, fullName, email, departmentId, status, null);
    }

    public static UserResponse from(TenantUser t) {
        return from(t.user(), t.departmentId(), t.tenantId());
    }

    /** Dùng cho đường ghi/đọc theo id: tenantId lấy từ tenant của phiên hiện tại (null nếu không có). */
    public static UserResponse from(User u, UUID departmentId) {
        return from(u, departmentId, TenantContext.current().orElse(null));
    }

    public static UserResponse from(User u, UUID departmentId, UUID tenantId) {
        return new UserResponse(u.id(), u.username(), u.fullName(), u.email(), departmentId, u.status(), tenantId);
    }
}
