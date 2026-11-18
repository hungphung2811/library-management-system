package lms.sales.banner.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lms.sales.banner.domain.Banner;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;

/** Bảng storefront_banners (RLS FORCE: tenant_isolation + platform_read_all). Nội dung công khai, không PII. */
@Entity
@Table(name = "storefront_banners")
class StorefrontBannerJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "subtitle")
    private String subtitle;

    @Column(name = "link_path")
    private String linkPath;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected StorefrontBannerJpaEntity() {
    }

    StorefrontBannerJpaEntity(Banner b) {
        this.tenantId = TenantContext.require();
        this.id = b.id();
        this.createdAt = b.createdAt();
        this.createdBy = b.createdBy();
        apply(b);
    }

    /** Chép các trường được phép đổi sau khi tạo. */
    void apply(Banner b) {
        this.title = b.title();
        this.subtitle = b.subtitle();
        this.linkPath = b.linkPath();
        this.sortOrder = b.sortOrder();
        this.active = b.active();
        this.startsAt = b.startsAt();
        this.endsAt = b.endsAt();
        this.updatedAt = b.updatedAt();
        this.updatedBy = b.updatedBy();
    }

    Banner toDomain() {
        return Banner.restore(id, tenantId, title, subtitle, linkPath, sortOrder, active, startsAt, endsAt,
                version == null ? 0 : version, createdAt, updatedAt, createdBy, updatedBy);
    }

    long getVersion() {
        return version == null ? 0 : version;
    }

    @Override
    public String toString() {
        return "StorefrontBannerJpaEntity[id=" + id + "]";
    }
}
