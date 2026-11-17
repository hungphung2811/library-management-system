package lms.catalog.catalog.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceKind;

/** Thể loại chuẩn (bảng categories), cây tối đa 2 cấp qua parent_id; @TenantId ở lớp cha ReferenceJpaEntity. */
@Entity
@Table(name = "categories")
class CategoryJpaEntity extends ReferenceJpaEntity {

    @Column(name = "parent_id")
    private UUID parentId;

    protected CategoryJpaEntity() {
    }

    CategoryJpaEntity(Reference r) {
        super(r);
        this.parentId = r.parentId();
    }

    @Override
    ReferenceKind kind() { return ReferenceKind.CATEGORY; }

    @Override
    UUID parentId() { return parentId; }
}
