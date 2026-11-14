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

@Entity
@Table(name = "roles")
class RoleJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String description;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_permissions", joinColumns = @JoinColumn(name = "role_id"))
    @Column(name = "permission_code", nullable = false)
    private Set<String> permissionCodes = new HashSet<>();

    protected RoleJpaEntity() {
    }

    RoleJpaEntity(UUID id, String name, String description, Set<String> permissionCodes) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.permissionCodes = new HashSet<>(permissionCodes);
    }

    UUID getId() { return id; }
    String getName() { return name; }
    String getDescription() { return description; }
    Set<String> getPermissionCodes() { return permissionCodes; }
}
