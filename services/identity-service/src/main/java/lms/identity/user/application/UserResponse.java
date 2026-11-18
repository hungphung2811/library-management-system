package lms.identity.user.application;

import lms.identity.user.domain.Gender;
import lms.identity.user.domain.TenantUser;
import lms.identity.user.domain.User;
import lms.identity.user.domain.UserPersonalInfo;
import lms.identity.user.domain.UserStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lms.tenant.TenantContext;

/**
 * departmentId: phòng ban trong tenant của dòng (tenant_memberships.department_id); tenantId: tenant của dòng
 * (ở phạm vi đọc ALL của platform admin mỗi dòng là một cặp người dùng - tenant, ADR-0008).
 *
 * <p>locked/lockedUntil: tài khoản đang bị khóa do đăng nhập sai nhiều lần (credentials.locked_until còn ở tương lai);
 * lockedUntil null khi không bị khóa. Chỉ các đường đọc/sửa có gắn {@link UserLockStatus} mới điền; còn lại mặc định
 * false/null.
 */
public record UserResponse(UUID id, String username, String fullName, String email, UUID departmentId, UserStatus status,
                           UUID tenantId, boolean locked, Instant lockedUntil,
                           String phone, LocalDate dateOfBirth, Gender gender, String address, String avatarUrl,
                           String jobTitle, String employeeCode) {

    public UserResponse(UUID id, String username, String fullName, String email, UUID departmentId, UserStatus status,
                        UUID tenantId, boolean locked, Instant lockedUntil) {
        this(id, username, fullName, email, departmentId, status, tenantId, locked, lockedUntil,
                null, null, null, null, null, null, null);
    }

    public UserResponse(UUID id, String username, String fullName, String email, UUID departmentId, UserStatus status,
                        UUID tenantId) {
        this(id, username, fullName, email, departmentId, status, tenantId, false, null);
    }

    public UserResponse(UUID id, String username, String fullName, String email, UUID departmentId, UserStatus status) {
        this(id, username, fullName, email, departmentId, status, null, false, null);
    }

    public static UserResponse from(TenantUser t) {
        return from(t.user(), t.departmentId(), t.tenantId());
    }

    /** Dùng cho đường ghi/đọc theo id: tenantId lấy từ tenant của phiên hiện tại (null nếu không có). */
    public static UserResponse from(User u, UUID departmentId) {
        return from(u, departmentId, TenantContext.current().orElse(null));
    }

    public static UserResponse from(User u, UUID departmentId, UUID tenantId) {
        UserPersonalInfo i = u.personalInfo();
        return new UserResponse(u.id(), u.username(), u.fullName(), u.email(), departmentId, u.status(), tenantId,
                false, null, i.phone(), i.dateOfBirth(), i.gender(), i.address(), i.avatarUrl(), i.jobTitle(),
                i.employeeCode());
    }

    /** Bản sao có trạng thái khóa; lockedUntil null nghĩa là không bị khóa. */
    public UserResponse withLock(Instant lockedUntil) {
        return new UserResponse(id, username, fullName, email, departmentId, status, tenantId, lockedUntil != null,
                lockedUntil, phone, dateOfBirth, gender, address, avatarUrl, jobTitle, employeeCode);
    }
}
