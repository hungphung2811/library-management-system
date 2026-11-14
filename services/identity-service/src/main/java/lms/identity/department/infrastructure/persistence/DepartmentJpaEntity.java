package lms.identity.department.infrastructure.persistence;

import lms.identity.department.domain.DepartmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "departments")
class DepartmentJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "parent_id")
    private UUID parentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DepartmentStatus status;

    protected DepartmentJpaEntity() {
    }

    DepartmentJpaEntity(UUID id, String code, String name, UUID parentId, DepartmentStatus status) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.parentId = parentId;
        this.status = status;
    }

    UUID getId() { return id; }
    String getCode() { return code; }
    String getName() { return name; }
    UUID getParentId() { return parentId; }
    DepartmentStatus getStatus() { return status; }
}
