package lms.identity.contract.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** Bảng chỉ thêm tenant_contract_payments (bất biến: @Immutable + trigger + REVOKE UPDATE/DELETE). */
@Entity
@Immutable
@Table(name = "tenant_contract_payments")
class ContractPaymentJpaEntity {

    @Id
    private UUID id;

    @Column(name = "contract_id", nullable = false)
    private UUID contractId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "paid_on", nullable = false)
    private LocalDate paidOn;

    @Column(nullable = false)
    private long amount;

    private String note;

    private String reference;

    @Column(name = "reverses_payment_id")
    private UUID reversesPaymentId;

    @Column(name = "recorded_by")
    private UUID recordedBy;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected ContractPaymentJpaEntity() {
    }

    ContractPaymentJpaEntity(UUID id, UUID contractId, UUID tenantId, LocalDate paidOn, long amount, String note,
                             String reference, UUID reversesPaymentId, UUID recordedBy, Instant recordedAt) {
        this.id = id;
        this.contractId = contractId;
        this.tenantId = tenantId;
        this.paidOn = paidOn;
        this.amount = amount;
        this.note = note;
        this.reference = reference;
        this.reversesPaymentId = reversesPaymentId;
        this.recordedBy = recordedBy;
        this.recordedAt = recordedAt;
    }

    UUID getId() { return id; }
    UUID getContractId() { return contractId; }
    UUID getTenantId() { return tenantId; }
    LocalDate getPaidOn() { return paidOn; }
    long getAmount() { return amount; }
    String getNote() { return note; }
    String getReference() { return reference; }
    UUID getReversesPaymentId() { return reversesPaymentId; }
    UUID getRecordedBy() { return recordedBy; }
    Instant getRecordedAt() { return recordedAt; }
}
