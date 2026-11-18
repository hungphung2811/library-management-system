package lms.catalog.catalog.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lms.catalog.catalog.domain.Reference;
import lms.catalog.catalog.domain.ReferenceExtras;
import lms.catalog.catalog.domain.ReferenceKind;

/** Tác giả chuẩn (bảng authors); @TenantId ở lớp cha ReferenceJpaEntity. */
@Entity
@Table(name = "authors")
class AuthorJpaEntity extends ReferenceJpaEntity {

    @Column(name = "birth_year")
    private Integer birthYear;

    @Column(name = "death_year")
    private Integer deathYear;

    @Column(length = 100)
    private String nationality;

    @Column(name = "photo_url", length = 500)
    private String photoUrl;

    @Column(length = 500)
    private String website;

    protected AuthorJpaEntity() {
    }

    AuthorJpaEntity(Reference r) {
        super(r);
        ReferenceExtras x = r.extras();
        this.birthYear = x.birthYear();
        this.deathYear = x.deathYear();
        this.nationality = x.nationality();
        this.photoUrl = x.photoUrl();
        this.website = x.website();
    }

    @Override
    ReferenceExtras extras() {
        return ReferenceExtras.author(birthYear, deathYear, nationality, photoUrl, website);
    }

    @Override
    ReferenceKind kind() { return ReferenceKind.AUTHOR; }
}
