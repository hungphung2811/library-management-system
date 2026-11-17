package lms.catalog.catalog.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceKind;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;

/**
 * Phần chung của bốn bảng danh mục chuẩn (authors, publishers, categories, shelf_locations): thuộc một tenant
 * (@TenantId + RLS), tên chuẩn hóa ở {@code name} và khóa so trùng chữ thường ở {@code nameKey}.
 */
@MappedSuperclass
abstract class ReferenceJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(name = "name_key", nullable = false)
    private String nameKey;

    @Column(length = 500)
    private String description;

    @Column(nullable = false)
    private boolean active;

    protected ReferenceJpaEntity() {
    }

    protected ReferenceJpaEntity(Reference r) {
        // Điền sẵn tenant của phiên (như TitleJpaEntity): thiếu tenant thì ném TenantRequiredException (fail-closed).
        this.tenantId = TenantContext.require();
        this.id = r.id();
        this.name = r.name();
        this.nameKey = r.nameKey();
        this.description = r.description();
        this.active = r.active();
    }

    UUID getId() { return id; }
    UUID getTenantId() { return tenantId; }
    String getName() { return name; }
    String getNameKey() { return nameKey; }
    String getDescription() { return description; }
    boolean isActive() { return active; }

    abstract ReferenceKind kind();

    UUID parentId() { return null; }

    Reference toDomain() {
        return Reference.restore(id, kind(), tenantId, name, description, parentId(), active);
    }
}
