package lms.sales.shift.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lms.sales.shift.domain.CashierShift;
import lms.sales.shift.domain.ShiftStatus;
import lms.sales.shift.domain.VarianceStatus;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;

/** Ca thu ngân (ADR-0030); khóa lạc quan @Version, mở lại/đóng/duyệt cập nhật cùng một dòng. */
@Entity
@Table(name = "cashier_shifts")
class CashierShiftJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "shift_no", nullable = false, updatable = false)
    private String shiftNo;

    @Column(name = "cashier_user_id", nullable = false, updatable = false)
    private UUID cashierUserId;

    @Column(name = "register_label", updatable = false)
    private String registerLabel;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "opened_at", nullable = false, updatable = false)
    private Instant openedAt;

    @Column(name = "opening_float", nullable = false, updatable = false)
    private long openingFloat;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by")
    private UUID closedBy;

    @Column(name = "counted_cash")
    private Long countedCash;

    @Column(name = "expected_cash")
    private Long expectedCash;

    @Column(name = "variance")
    private Long variance;

    @Column(name = "variance_reason")
    private String varianceReason;

    @Column(name = "variance_status", nullable = false)
    private String varianceStatus;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approval_note")
    private String approvalNote;

    @Column(name = "reopen_count", nullable = false)
    private int reopenCount;

    @Column(name = "client_request_id", nullable = false, updatable = false)
    private UUID clientRequestId;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected CashierShiftJpaEntity() {
    }

    CashierShiftJpaEntity(CashierShift s) {
        this.tenantId = TenantContext.require();
        this.id = s.id();
        this.shiftNo = s.shiftNo();
        this.cashierUserId = s.cashierUserId();
        this.registerLabel = s.registerLabel();
        this.openedAt = s.openedAt();
        this.openingFloat = s.openingFloat();
        this.clientRequestId = s.clientRequestId();
        apply(s);
        // version để null: Hibernate khởi tạo 0 khi persist
    }

    void apply(CashierShift s) {
        this.status = s.status().name();
        this.closedAt = s.closedAt();
        this.closedBy = s.closedBy();
        this.countedCash = s.countedCash();
        this.expectedCash = s.expectedCash();
        this.variance = s.variance();
        this.varianceReason = s.varianceReason();
        this.varianceStatus = s.varianceStatus().name();
        this.approvedBy = s.approvedBy();
        this.approvedAt = s.approvedAt();
        this.approvalNote = s.approvalNote();
        this.reopenCount = s.reopenCount();
    }

    CashierShift toDomain() {
        return CashierShift.restore(new CashierShift.Snapshot(id, tenantId, shiftNo, cashierUserId, registerLabel,
                ShiftStatus.valueOf(status), openedAt, openingFloat, closedAt, closedBy, countedCash, expectedCash,
                variance, varianceReason, VarianceStatus.valueOf(varianceStatus), approvedBy, approvedAt,
                approvalNote, reopenCount, clientRequestId, version == null ? 0 : version));
    }

    UUID getId() { return id; }
    long getVersion() { return version == null ? 0 : version; }
}
