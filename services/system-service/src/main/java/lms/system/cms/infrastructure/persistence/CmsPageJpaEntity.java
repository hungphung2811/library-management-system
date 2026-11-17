package lms.system.cms.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lms.system.cms.domain.CmsPage;

/**
 * Trang CMS cấp nền tảng (system V3): KHÔNG có tenant_id, không @TenantId, không RLS (ghi trong
 * TENANT_EXEMPT_TABLES). {@code version} là khóa lạc quan. Không có SQL native.
 */
@Entity
@Table(name = "cms_pages")
class CmsPageJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false, updatable = false, length = 80)
    private String slug;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "body_markdown", nullable = false, length = 50000)
    private String bodyMarkdown;

    @Column(name = "published_title", length = 200)
    private String publishedTitle;

    @Column(name = "published_body_markdown", length = 50000)
    private String publishedBodyMarkdown;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected CmsPageJpaEntity() {
    }

    static CmsPageJpaEntity create(CmsPage page) {
        CmsPageJpaEntity e = new CmsPageJpaEntity();
        e.id = page.id();
        e.slug = page.slug();
        e.createdAt = page.createdAt();
        e.apply(page);
        return e;
    }

    /** Ghi phần có thể đổi; slug, created_at và version không đổi qua đây. */
    void apply(CmsPage page) {
        this.title = page.title();
        this.bodyMarkdown = page.bodyMarkdown();
        this.publishedTitle = page.publishedTitle();
        this.publishedBodyMarkdown = page.publishedBodyMarkdown();
        this.publishedAt = page.publishedAt();
        this.sortOrder = page.sortOrder();
        this.updatedAt = page.updatedAt();
        this.updatedBy = page.updatedBy();
    }

    CmsPage toDomain() {
        return new CmsPage(id, slug, title, bodyMarkdown, publishedTitle, publishedBodyMarkdown, publishedAt,
                sortOrder, version == null ? 0L : version, createdAt, updatedAt, updatedBy);
    }
}
