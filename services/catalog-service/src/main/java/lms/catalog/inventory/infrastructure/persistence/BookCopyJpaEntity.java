package lms.catalog.inventory.infrastructure.persistence;

import lms.catalog.inventory.domain.CopyStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;
import lms.catalog.inventory.domain.AcquisitionSource;
import lms.catalog.inventory.domain.ConditionGrade;
import lms.catalog.inventory.domain.CopyAcquisition;
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

    @Column(name = "acquired_date")
    private LocalDate acquiredDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "acquisition_source", length = 20)
    private AcquisitionSource acquisitionSource;

    @Column(name = "purchase_price")
    private Long purchasePrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_grade", length = 10)
    private ConditionGrade conditionGrade;

    @Column(name = "condition_note", length = 300)
    private String conditionNote;

    @Column(name = "last_inventory_date")
    private LocalDate lastInventoryDate;

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

    /** Ghi thông tin nhập kho từ domain. */
    BookCopyJpaEntity withAcquisition(CopyAcquisition a) {
        this.acquiredDate = a.acquiredDate();
        this.acquisitionSource = a.acquisitionSource();
        this.purchasePrice = a.purchasePrice();
        this.conditionGrade = a.conditionGrade();
        this.conditionNote = a.conditionNote();
        this.lastInventoryDate = a.lastInventoryDate();
        return this;
    }

    CopyAcquisition toAcquisition() {
        return new CopyAcquisition(acquiredDate, acquisitionSource, purchasePrice, conditionGrade, conditionNote,
                lastInventoryDate);
    }

    UUID getId() { return id; }
    UUID getTenantId() { return tenantId; }
    UUID getTitleId() { return titleId; }
    String getBarcode() { return barcode; }
    CopyStatus getStatus() { return status; }
    UUID getShelfLocationId() { return shelfLocationId; }
}
