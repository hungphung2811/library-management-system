package lms.catalog.catalog.infrastructure.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceKind;

/** Tác giả chuẩn (bảng authors); @TenantId ở lớp cha ReferenceJpaEntity. */
@Entity
@Table(name = "authors")
class AuthorJpaEntity extends ReferenceJpaEntity {

    protected AuthorJpaEntity() {
    }

    AuthorJpaEntity(Reference r) {
        super(r);
    }

    @Override
    ReferenceKind kind() { return ReferenceKind.AUTHOR; }
}
