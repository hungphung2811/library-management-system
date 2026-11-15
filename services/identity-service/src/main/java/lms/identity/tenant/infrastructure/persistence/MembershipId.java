package lms.identity.tenant.infrastructure.persistence;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Khóa chính kép (user_id, tenant_id) của tenant_memberships. */
class MembershipId implements Serializable {

    private UUID userId;
    private UUID tenantId;

    protected MembershipId() {
    }

    MembershipId(UUID userId, UUID tenantId) {
        this.userId = userId;
        this.tenantId = tenantId;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof MembershipId other
                && Objects.equals(userId, other.userId) && Objects.equals(tenantId, other.tenantId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, tenantId);
    }
}
