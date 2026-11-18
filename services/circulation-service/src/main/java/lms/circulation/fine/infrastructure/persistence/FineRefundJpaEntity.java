package lms.circulation.fine.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lms.circulation.fine.domain.ApprovalStatus;
import lms.circulation.fine.domain.FineRefund;
import lms.circulation.fine.domain.FineStatus;
import lms.circulation.fine.domain.RefundMethod;
import lms.circulation.fine.domain.RefundMode;
import lms.circulation.fine.domain.RestoreStatus;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;

/**
 * Đề nghị hoàn khoản (migration circulation V12): tenant_id do Hibernate điền/lọc (@TenantId), RLS lọc thêm ở DB. Dòng
 * APPROVED là bút toán REFUND (chỉ ghi nhận, không chuyển tiền thật); không bao giờ bị xóa.
 */
@Entity
@Table(name = "fine_refunds")
class FineRefundJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "fine_id", nullable = false)
    private UUID fineId;

    @Column(name = "borrowing_id", nullable = false)
    private UUID borrowingId;

    @Column(name = "book_copy_id", nullable = false)
    private UUID bookCopyId;

    @Column(name = "reader_id", nullable = false)
    private UUID readerId;

    @Column(name = "lost_on", nullable = false)
    private LocalDate lostOn;

    @Column(name = "found_on", nullable = false)
    private LocalDate foundOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "restore_status", nullable = false, length = 10)
    private RestoreStatus restoreStatus;

    @Column(name = "check_note", length = 500)
    private String checkNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_method", nullable = false, length = 20)
    private RefundMethod refundMethod;

    @Column(name = "reference_code", length = 100)
    private String referenceCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private RefundMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "fine_status_at_request", nullable = false, length = 10)
    private FineStatus fineStatusAtRequest;

    @Column(name = "collected_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal collectedAmount;

    @Column(name = "processing_fee", nullable = false, precision = 14, scale = 2)
    private BigDecimal processingFee;

    @Column(name = "refund_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal refundAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ApprovalStatus status;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    protected FineRefundJpaEntity() {
    }

    FineRefundJpaEntity(FineRefund r) {
        this.tenantId = TenantContext.require();
        this.id = r.id();
        this.fineId = r.fineId();
        this.borrowingId = r.borrowingId();
        this.bookCopyId = r.bookCopyId();
        this.readerId = r.readerId();
        this.lostOn = r.lostOn();
        this.foundOn = r.foundOn();
        this.restoreStatus = r.restoreStatus();
        this.checkNote = r.checkNote();
        this.refundMethod = r.method();
        this.referenceCode = r.referenceCode();
        this.mode = r.mode();
        this.fineStatusAtRequest = r.fineStatusAtRequest();
        this.collectedAmount = r.collectedAmount();
        this.processingFee = r.processingFee();
        this.refundAmount = r.refundAmount();
        this.status = r.status();
        this.requestedBy = r.requestedBy();
        this.requestedAt = r.requestedAt();
        this.decidedBy = r.decidedBy();
        this.decidedAt = r.decidedAt();
        this.decisionNote = r.decisionNote();
    }

    FineRefund toDomain() {
        return FineRefund.restore(id, fineId, borrowingId, bookCopyId, readerId, lostOn, foundOn, restoreStatus,
                checkNote, refundMethod, referenceCode, mode, fineStatusAtRequest, collectedAmount, processingFee,
                refundAmount, status, requestedBy, requestedAt, decidedBy, decidedAt, decisionNote, tenantId);
    }
}
