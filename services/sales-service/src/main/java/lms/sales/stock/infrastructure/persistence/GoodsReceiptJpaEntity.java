package lms.sales.stock.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;

/** Phiếu nhập kho (đầu phiếu); các dòng ở {@link GoodsReceiptLineJpaEntity}. Khóa lạc quan bằng version. */
@Entity
@Table(name = "goods_receipts")
class GoodsReceiptJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "receipt_no", nullable = false, updatable = false)
    private String receiptNo;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private UUID supplierId;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "client_request_id", nullable = false, updatable = false)
    private UUID clientRequestId;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "confirmed_by")
    private UUID confirmedBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected GoodsReceiptJpaEntity() {
    }

    GoodsReceiptJpaEntity(UUID id, String receiptNo, UUID supplierId, String status, UUID clientRequestId,
                          UUID createdBy, Instant createdAt) {
        this.tenantId = TenantContext.require();
        this.id = id;
        this.receiptNo = receiptNo;
        this.supplierId = supplierId;
        this.status = status;
        this.clientRequestId = clientRequestId;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        // version để null: Hibernate khởi tạo 0 khi persist (version khác null bị coi là entity detached)
    }

    void updateState(String status, Instant confirmedAt, UUID confirmedBy) {
        this.status = status;
        this.confirmedAt = confirmedAt;
        this.confirmedBy = confirmedBy;
    }

    UUID getId() { return id; }
    UUID getTenantId() { return tenantId; }
    String getReceiptNo() { return receiptNo; }
    UUID getSupplierId() { return supplierId; }
    String getStatus() { return status; }
    UUID getClientRequestId() { return clientRequestId; }
    UUID getCreatedBy() { return createdBy; }
    Instant getCreatedAt() { return createdAt; }
    Instant getConfirmedAt() { return confirmedAt; }
    UUID getConfirmedBy() { return confirmedBy; }
    long getVersion() { return version; }
}
