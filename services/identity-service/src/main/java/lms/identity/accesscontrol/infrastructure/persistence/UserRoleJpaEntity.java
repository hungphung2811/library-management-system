package lms.identity.accesscontrol.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import lms.tenant.TenantContext;

/**
 * Một dòng của bảng user_roles (userId, roleId) trong tenant hiện tại (@TenantId; khóa ngoại kép với roles bảo đảm
 * trùng tenant của role). Aggregate UserRoleAssignment được dựng lại trong adapter.
 */
@Entity
@Table(name = "user_roles")
@IdClass(UserRoleJpaEntity.Key.class)
class UserRoleJpaEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "role_id")
    private UUID roleId;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    protected UserRoleJpaEntity() {
    }

    UserRoleJpaEntity(UUID userId, UUID roleId) {
        // Điền sẵn tenant của phiên: merge (save entity mới dựng) không ghi đè tenant_id thành null, insert khớp
        // tenant phiên; không có tenant thì ném TenantRequiredException (fail-closed). Cột updatable=false.
        this.tenantId = TenantContext.require();
        this.userId = userId;
        this.roleId = roleId;
    }

    UUID getUserId() { return userId; }
    UUID getRoleId() { return roleId; }

    public static class Key implements Serializable {
        private UUID userId;
        private UUID roleId;

        public Key() {
        }

        public Key(UUID userId, UUID roleId) {
            this.userId = userId;
            this.roleId = roleId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(userId, k.userId) && Objects.equals(roleId, k.roleId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, roleId);
        }
    }
}
