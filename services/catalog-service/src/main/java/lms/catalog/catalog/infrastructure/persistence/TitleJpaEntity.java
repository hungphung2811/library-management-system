package lms.catalog.catalog.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;
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

    protected TitleJpaEntity() {
    }

    TitleJpaEntity(UUID id, String name, String author, String isbn, BigDecimal coverPrice) {
        // Điền sẵn tenant của phiên: merge (save entity mới dựng) không ghi đè tenant_id thành null, insert khớp
        // tenant phiên; không có tenant thì ném TenantRequiredException (fail-closed). Cột updatable=false.
        this.tenantId = TenantContext.require();
        this.id = id;
        this.name = name;
        this.author = author;
        this.isbn = isbn;
        this.coverPrice = coverPrice;
    }

    UUID getId() { return id; }
    String getName() { return name; }
    String getAuthor() { return author; }
    String getIsbn() { return isbn; }
    BigDecimal getCoverPrice() { return coverPrice; }
}
