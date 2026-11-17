package lms.identity.user.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * Góc nhìn CHỈ ĐỌC của context user lên tenant_memberships (bảng toàn cục, không RLS, R2): lọc người dùng theo tenant
 * và lấy phòng ban trong tenant. Việc ghi membership đi qua tầng application của context tenant.
 */
@Entity
@Immutable
@Table(name = "tenant_memberships")
@IdClass(UserMembershipJpaEntity.Key.class)
class UserMembershipJpaEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "department_id")
    private UUID departmentId;

    /** Hồ sơ người dùng (chỉ đọc), để lọc/sắp xếp theo thông tin người dùng khi tìm theo membership. */
    @jakarta.persistence.ManyToOne(fetch = jakarta.persistence.FetchType.LAZY)
    @jakarta.persistence.JoinColumn(name = "user_id", insertable = false, updatable = false)
    private UserJpaEntity user;

    protected UserMembershipJpaEntity() {
    }

    UUID getUserId() { return userId; }
    UUID getTenantId() { return tenantId; }
    UUID getDepartmentId() { return departmentId; }
    UserJpaEntity getUser() { return user; }

    static class Key implements Serializable {
        private UUID userId;
        private UUID tenantId;

        protected Key() {
        }

        Key(UUID userId, UUID tenantId) {
            this.userId = userId;
            this.tenantId = tenantId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(userId, k.userId) && Objects.equals(tenantId, k.tenantId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, tenantId);
        }
    }
}
