package lms.identity.department.infrastructure.persistence;

import lms.identity.department.domain.DepartmentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/** Phòng ban thuộc một tenant (@TenantId + RLS); mã unique theo tenant, phòng ban cha cùng tenant (ràng buộc ở migration). */
@Entity
@Table(name = "departments")
class DepartmentJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
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
