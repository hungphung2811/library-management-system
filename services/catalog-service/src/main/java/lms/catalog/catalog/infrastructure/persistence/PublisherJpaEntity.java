package lms.catalog.catalog.infrastructure.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceKind;

/** Nhà xuất bản chuẩn (bảng publishers); @TenantId ở lớp cha ReferenceJpaEntity. */
@Entity
@Table(name = "publishers")
class PublisherJpaEntity extends ReferenceJpaEntity {

    protected PublisherJpaEntity() {
    }

    PublisherJpaEntity(Reference r) {
        super(r);
    }

    @Override
    ReferenceKind kind() { return ReferenceKind.PUBLISHER; }
}
