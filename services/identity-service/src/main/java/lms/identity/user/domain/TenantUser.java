package lms.identity.user.domain;

import java.util.UUID;

/**
 * Người dùng nhìn từ một tenant: hồ sơ toàn cục cùng phòng ban trong tenant đó (tenant_memberships.department_id,
 * null nếu chưa gán). Chỉ tồn tại khi người dùng có membership ở tenant.
 */
public record TenantUser(User user, UUID tenantId, UUID departmentId) {

    public TenantUser {
        if (user == null) throw new InvalidUserException("user không được null");
        if (tenantId == null) throw new InvalidUserException("tenantId không được null");
    }
}
