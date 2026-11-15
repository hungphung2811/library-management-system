package lms.identity.tenant.domain;

import java.time.Instant;
import java.util.UUID;

/** Tư cách thành viên của một người dùng trong một tenant (khóa: userId + tenantId). */
public class Membership {

    private final UUID userId;
    private final UUID tenantId;
    private UUID departmentId;
    private MembershipStatus status;
    private final Instant joinedAt;

    private Membership(UUID userId, UUID tenantId, UUID departmentId, MembershipStatus status, Instant joinedAt) {
        if (userId == null) throw new InvalidTenantException("userId không được null");
        if (tenantId == null) throw new InvalidTenantException("tenantId không được null");
        if (status == null) throw new InvalidTenantException("Trạng thái không được null");
        this.userId = userId;
        this.tenantId = tenantId;
        this.departmentId = departmentId;
        this.status = status;
        this.joinedAt = joinedAt;
    }

    public static Membership create(UUID userId, UUID tenantId, UUID departmentId) {
        return new Membership(userId, tenantId, departmentId, MembershipStatus.ACTIVE, Instant.now());
    }

    public static Membership restore(UUID userId, UUID tenantId, UUID departmentId, MembershipStatus status,
                                     Instant joinedAt) {
        return new Membership(userId, tenantId, departmentId, status, joinedAt);
    }

    public void disable() {
        this.status = MembershipStatus.DISABLED;
    }

    public void enable() {
        this.status = MembershipStatus.ACTIVE;
    }

    public void moveToDepartment(UUID newDepartmentId) {
        this.departmentId = newDepartmentId;
    }

    public boolean isActive() {
        return status == MembershipStatus.ACTIVE;
    }

    public UUID userId() { return userId; }
    public UUID tenantId() { return tenantId; }
    public UUID departmentId() { return departmentId; }
    public MembershipStatus status() { return status; }
    public Instant joinedAt() { return joinedAt; }
}
