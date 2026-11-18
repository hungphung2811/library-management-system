package lms.catalog.catalog.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lms.catalog.catalog.domain.TitleExtras;
import lms.catalog.catalog.domain.TitleFormat;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.TenantId;
import lms.tenant.TenantContext;

/** Đầu sách thuộc một tenant: tenant_id do Hibernate điền/lọc theo TenantContext (@TenantId), RLS lọc thêm ở DB. */
@Entity
@Table(name = "titles")
class TitleJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String author;

    private String isbn;

    @Column(name = "cover_price", nullable = false)
    private BigDecimal coverPrice;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "preview_text", length = 2000)
    private String previewText;

    @Column(name = "publisher_id")
    private UUID publisherId;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "subtitle", length = 255)
    private String subtitle;

    @Column(name = "publication_year")
    private Integer publicationYear;

    @Column(name = "edition", length = 50)
    private String edition;

    @Column(name = "language", length = 8)
    private String language;

    @Column(name = "page_count")
    private Integer pageCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "format", length = 20)
    private TitleFormat format;

    @Column(name = "translator", length = 255)
    private String translator;

    @Column(name = "series", length = 255)
    private String series;

    @Column(name = "series_number")
    private Integer seriesNumber;

    @Column(name = "cover_image_url", length = 500)
    private String coverImageUrl;

    /** Cột text[] của Postgres; null khi không có nhãn. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "tags")
    private List<String> tags;

    protected TitleJpaEntity() {
    }

    TitleJpaEntity(UUID id, String name, String author, String isbn, BigDecimal coverPrice,
                   String description, String previewText, UUID publisherId, UUID categoryId) {
        // Điền sẵn tenant của phiên: merge (save entity mới dựng) không ghi đè tenant_id thành null, insert khớp
        // tenant phiên; không có tenant thì ném TenantRequiredException (fail-closed). Cột updatable=false.
        this.tenantId = TenantContext.require();
        this.id = id;
        this.name = name;
        this.author = author;
        this.isbn = isbn;
        this.coverPrice = coverPrice;
        this.description = description;
        this.previewText = previewText;
        this.publisherId = publisherId;
        this.categoryId = categoryId;
    }

    /** Ghi thông tin bổ sung từ domain (null/rỗng nghĩa là không có). */
    TitleJpaEntity withExtras(TitleExtras x) {
        this.subtitle = x.subtitle();
        this.publicationYear = x.publicationYear();
        this.edition = x.edition();
        this.language = x.language();
        this.pageCount = x.pageCount();
        this.format = x.format();
        this.translator = x.translator();
        this.series = x.series();
        this.seriesNumber = x.seriesNumber();
        this.coverImageUrl = x.coverImageUrl();
        this.tags = x.tags().isEmpty() ? null : new java.util.ArrayList<>(x.tags());
        return this;
    }

    TitleExtras toExtras() {
        return new TitleExtras(subtitle, publicationYear, edition, language, pageCount, format, translator, series,
                seriesNumber, coverImageUrl, tags);
    }

    UUID getId() { return id; }
    UUID getTenantId() { return tenantId; }
    String getName() { return name; }
    String getAuthor() { return author; }
    String getIsbn() { return isbn; }
    BigDecimal getCoverPrice() { return coverPrice; }
    String getDescription() { return description; }
    String getPreviewText() { return previewText; }
    UUID getPublisherId() { return publisherId; }
    UUID getCategoryId() { return categoryId; }
}
