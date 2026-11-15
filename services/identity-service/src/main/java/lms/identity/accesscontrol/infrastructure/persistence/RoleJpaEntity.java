package lms.identity.accesscontrol.infrastructure.persistence;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** Role thuộc một tenant: tenant_id do Hibernate điền/lọc theo TenantContext (@TenantId), RLS lọc thêm ở DB. */
@Entity
@Table(name = "roles")
class RoleJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** Unique theo tenant (tenant_id, name), ràng buộc nằm ở migration. */
    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    @Column(name = "is_admin", nullable = false)
    private boolean admin;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_permissions", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "permission_code", nullable = false)
    private Set<String> permissionCodes = new HashSet<>();

    protected RoleJpaEntity() {
    }

    RoleJpaEntity(UUID id, String name, String description, boolean admin, Set<String> permissionCodes) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.admin = admin;
        this.permissionCodes = new HashSet<>(permissionCodes);
    }

    UUID getId() { return id; }
    String getName() { return name; }
    String getDescription() { return description; }
    boolean isAdmin() { return admin; }
    Set<String> getPermissionCodes() { return permissionCodes; }
}
