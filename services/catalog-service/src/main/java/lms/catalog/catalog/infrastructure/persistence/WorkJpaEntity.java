package lms.catalog.catalog.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;
import java.util.UUID;
import lms.catalog.catalog.domain.Work;
import lms.catalog.catalog.domain.WorkKind;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;

/** Tác phẩm thuộc một tenant (ADR-0026): @TenantId + RLS. created_at do DB điền (DEFAULT now()), không ánh xạ. */
@Entity
@Table(name = "works")
class WorkJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String title;

    @Column(name = "original_title")
    private String originalTitle;

    @Column(name = "original_language", length = 8)
    private String originalLanguage;

    @Column(nullable = false)
    private String author;

    @Column(length = 500)
    private String description;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "series_id")
    private UUID seriesId;

    @Column(name = "series_number")
    private Integer seriesNumber;

    @Column(name = "series_label", length = 50)
    private String seriesLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkKind kind;

    protected WorkJpaEntity() {
    }

    WorkJpaEntity(Work w) {
        // Như TitleJpaEntity: điền sẵn tenant của phiên (fail-closed khi không có tenant).
        this.tenantId = TenantContext.require();
        this.id = w.id();
        this.title = w.title();
        this.originalTitle = w.originalTitle();
        this.originalLanguage = w.originalLanguage();
        this.author = w.author();
        this.description = w.description();
        this.categoryId = w.categoryId();
        this.seriesId = w.seriesId();
        this.seriesNumber = w.seriesNumber();
        this.seriesLabel = w.seriesLabel();
        this.kind = w.kind();
    }

    UUID getId() { return id; }

    Work toDomain(List<UUID> authorIds) {
        return Work.restore(id, tenantId, title, originalTitle, originalLanguage, author, description, categoryId,
                seriesId, seriesNumber, seriesLabel, kind, authorIds);
    }
}
