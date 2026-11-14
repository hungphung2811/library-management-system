package lms.identity.accesscontrol.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Một dòng của bảng user_roles (userId, roleId). Aggregate UserRoleAssignment được dựng lại trong adapter. */
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

    protected UserRoleJpaEntity() {
    }

    UserRoleJpaEntity(UUID userId, UUID roleId) {
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
