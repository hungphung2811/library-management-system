package lms.identity.accesscontrol.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "permissions")
class PermissionJpaEntity {

    @Id
    private String code;

    @Column(nullable = false)
    private String description;

    protected PermissionJpaEntity() {
    }

    PermissionJpaEntity(String code, String description) {
        this.code = code;
        this.description = description;
    }

    String getCode() { return code; }
    String getDescription() { return description; }
}
