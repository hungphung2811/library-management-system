package lms.circulation.fine.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lms.circulation.fine.domain.AdjustmentKind;
import lms.circulation.fine.domain.ApprovalStatus;
import lms.circulation.fine.domain.FineAdjustmentRequest;
import lms.circulation.fine.domain.FineReason;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;

/**
 * Đề nghị miễn/giảm (migration circulation V12): tenant_id do Hibernate điền/lọc (@TenantId), RLS lọc thêm ở DB. Không
 * có truy vấn native. Dòng không bao giờ bị xóa; chỉ chuyển PENDING sang APPROVED/REJECTED.
 */
@Entity
@Table(name = "fine_adjustment_requests")
class FineAdjustmentJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "fine_id", nullable = false)
    private UUID fineId;

    @Column(name = "reader_id", nullable = false)
    private UUID readerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "fine_reason", nullable = false, length = 20)
    private FineReason fineReason;

    @Column(name = "fine_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal fineAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AdjustmentKind kind;

    @Column(name = "payable_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal payableAmount;

    @Column(nullable = false, length = 500)
    private String reason;

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

    protected FineAdjustmentJpaEntity() {
    }

    FineAdjustmentJpaEntity(FineAdjustmentRequest r) {
        this.tenantId = TenantContext.require();
        this.id = r.id();
        this.fineId = r.fineId();
        this.readerId = r.readerId();
        this.fineReason = r.fineReason();
        this.fineAmount = r.fineAmount();
        this.kind = r.kind();
        this.payableAmount = r.payableAmount();
        this.reason = r.reason();
        this.status = r.status();
        this.requestedBy = r.requestedBy();
        this.requestedAt = r.requestedAt();
        this.decidedBy = r.decidedBy();
        this.decidedAt = r.decidedAt();
        this.decisionNote = r.decisionNote();
    }

    FineAdjustmentRequest toDomain() {
        return FineAdjustmentRequest.restore(id, fineId, readerId, fineReason, fineAmount, kind, payableAmount, reason,
                status, requestedBy, requestedAt, decidedBy, decidedAt, decisionNote, tenantId);
    }
}
