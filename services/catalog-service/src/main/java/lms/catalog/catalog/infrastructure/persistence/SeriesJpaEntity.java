package lms.catalog.catalog.infrastructure.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceKind;

/** Bộ sách (bảng series, ADR-0026); @TenantId ở lớp cha ReferenceJpaEntity. */
@Entity
@Table(name = "series")
class SeriesJpaEntity extends ReferenceJpaEntity {

    protected SeriesJpaEntity() {
    }

    SeriesJpaEntity(Reference r) {
        super(r);
    }

    @Override
    ReferenceKind kind() { return ReferenceKind.SERIES; }
}
