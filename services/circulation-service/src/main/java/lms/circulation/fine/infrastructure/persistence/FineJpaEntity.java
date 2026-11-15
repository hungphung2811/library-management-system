package lms.circulation.fine.infrastructure.persistence;

import lms.circulation.fine.domain.FineReason;
import lms.circulation.fine.domain.FineStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.TenantId;
import lms.tenant.TenantContext;

/**
 * Phí phạt thuộc một tenant: tenant_id do Hibernate điền/lọc theo TenantContext (@TenantId), RLS lọc thêm ở DB
 * (migration circulation V6). Mọi truy vấn của SpringDataFineRepository là JPQL/derived query, không có SQL native.
 */
@Entity
@Table(name = "fines")
class FineJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "reader_id", nullable = false)
    private UUID readerId;

    @Column(name = "borrowing_id", nullable = false)
    private UUID borrowingId;

    @Column(name = "book_copy_id", nullable = false)
    private UUID bookCopyId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FineReason reason;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FineStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    protected FineJpaEntity() {
    }

    FineJpaEntity(UUID id, UUID readerId, UUID borrowingId, UUID bookCopyId, FineReason reason, BigDecimal amount,
                  FineStatus status, Instant createdAt, Instant paidAt) {
        // Điền sẵn tenant của phiên: merge (save entity mới dựng, kể cả khi thu phạt cập nhật bản ghi cũ) không ghi đè
        // tenant_id thành null; không có tenant thì ném TenantRequiredException (fail-closed). Cột updatable=false.
        this.tenantId = TenantContext.require();
        this.id = id;
        this.readerId = readerId;
        this.borrowingId = borrowingId;
        this.bookCopyId = bookCopyId;
        this.reason = reason;
        this.amount = amount;
        this.status = status;
        this.createdAt = createdAt;
        this.paidAt = paidAt;
    }

    UUID getId() { return id; }
    UUID getReaderId() { return readerId; }
    UUID getBorrowingId() { return borrowingId; }
    UUID getBookCopyId() { return bookCopyId; }
    FineReason getReason() { return reason; }
    BigDecimal getAmount() { return amount; }
    FineStatus getStatus() { return status; }
    Instant getCreatedAt() { return createdAt; }
    Instant getPaidAt() { return paidAt; }
}
