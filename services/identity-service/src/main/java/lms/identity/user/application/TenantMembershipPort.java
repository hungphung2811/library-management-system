package lms.identity.user.application;

import java.util.UUID;

/**
 * Cổng sang context tenant: ghi membership và phòng ban của người dùng trong một tenant (tenant_memberships). Phòng ban
 * là dữ liệu của tenant nên chỉ nằm ở membership; token đọc phòng ban từ đây.
 */
public interface TenantMembershipPort {

    /** Thêm vào tenant (idempotent, không bật lại membership đã tắt); departmentId khác null thì ghi vào membership. */
    void join(UUID userId, UUID tenantId, UUID departmentId);

    /** Đổi phòng ban trong tenant; departmentId null = bỏ phòng ban. */
    void changeDepartment(UUID userId, UUID tenantId, UUID departmentId);
}
