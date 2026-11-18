package lms.catalog.catalog.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceExtras;
import lms.catalog.catalog.domain.ReferenceKind;

/** Nhà xuất bản chuẩn (bảng publishers); @TenantId ở lớp cha ReferenceJpaEntity. */
@Entity
@Table(name = "publishers")
class PublisherJpaEntity extends ReferenceJpaEntity {

    @Column(length = 100)
    private String country;

    @Column(name = "founded_year")
    private Integer foundedYear;

    @Column(length = 500)
    private String website;

    @Column(length = 500)
    private String address;

    protected PublisherJpaEntity() {
    }

    PublisherJpaEntity(Reference r) {
        super(r);
        ReferenceExtras x = r.extras();
        this.country = x.country();
        this.foundedYear = x.foundedYear();
        this.website = x.website();
        this.address = x.address();
    }

    @Override
    ReferenceExtras extras() {
        return ReferenceExtras.publisher(country, foundedYear, website, address);
    }

    @Override
    ReferenceKind kind() { return ReferenceKind.PUBLISHER; }
}
