package lms.catalog.inventory.infrastructure.persistence;

import lms.catalog.inventory.domain.CopyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import lms.tenant.TenantContext;

/** Bản sao thuộc một tenant (@TenantId + RLS); mã vạch unique theo tenant (ràng buộc ở migration). */
@Entity
@Table(name = "book_copies")
class BookCopyJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "title_id", nullable = false)
    private UUID titleId;

    @Column(nullable = false)
    private String barcode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CopyStatus status;

    @Column(name = "shelf_location_id")
    private UUID shelfLocationId;

    protected BookCopyJpaEntity() {
    }

    BookCopyJpaEntity(UUID id, UUID titleId, String barcode, CopyStatus status, UUID shelfLocationId) {
        // Điền sẵn tenant của phiên: merge (save entity mới dựng) không ghi đè tenant_id thành null, insert khớp
        // tenant phiên; không có tenant thì ném TenantRequiredException (fail-closed). Cột updatable=false.
        this.tenantId = TenantContext.require();
        this.id = id;
        this.titleId = titleId;
        this.barcode = barcode;
        this.status = status;
        this.shelfLocationId = shelfLocationId;
    }

    UUID getId() { return id; }
    UUID getTenantId() { return tenantId; }
    UUID getTitleId() { return titleId; }
    String getBarcode() { return barcode; }
    CopyStatus getStatus() { return status; }
    UUID getShelfLocationId() { return shelfLocationId; }
}
