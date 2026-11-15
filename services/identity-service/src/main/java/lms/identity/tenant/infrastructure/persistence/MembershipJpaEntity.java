package lms.identity.tenant.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lms.identity.tenant.domain.MembershipStatus;

/** Bảng toàn cục (không @TenantId, không RLS): cần đọc được trước khi biết tenant hiện tại. */
@Entity
@Table(name = "tenant_memberships")
@IdClass(MembershipId.class)
class MembershipJpaEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "department_id")
    private UUID departmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MembershipStatus status;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    protected MembershipJpaEntity() {
    }

    MembershipJpaEntity(UUID userId, UUID tenantId, UUID departmentId, MembershipStatus status, Instant joinedAt) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.departmentId = departmentId;
        this.status = status;
        this.joinedAt = joinedAt;
    }

    UUID getUserId() { return userId; }
    UUID getTenantId() { return tenantId; }
    UUID getDepartmentId() { return departmentId; }
    MembershipStatus getStatus() { return status; }
    Instant getJoinedAt() { return joinedAt; }
}
