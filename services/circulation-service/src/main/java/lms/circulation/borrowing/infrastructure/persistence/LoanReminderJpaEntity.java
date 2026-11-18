package lms.circulation.borrowing.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDate;
import java.util.UUID;
import lms.circulation.borrowing.domain.LoanReminderKind;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;
import org.springframework.data.domain.Persistable;

/**
 * Một lần nhắc hạn đã phát (migration circulation V15): tenant_id do Hibernate điền/lọc (@TenantId), RLS lọc thêm ở DB.
 * Chỉ chèn, không sửa. Persistable báo "mới" để Spring Data gọi persist (không select-before-insert cho từng dòng).
 */
@Entity
@Table(name = "loan_reminders")
class LoanReminderJpaEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "borrowing_id", nullable = false, updatable = false)
    private UUID borrowingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 10)
    private LoanReminderKind kind;

    @Column(name = "due_date", nullable = false, updatable = false)
    private LocalDate dueDate;

    @Column(name = "seq", nullable = false, updatable = false)
    private int seq;

    @Column(name = "sent_on", nullable = false, updatable = false)
    private LocalDate sentOn;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    protected LoanReminderJpaEntity() {
    }

    LoanReminderJpaEntity(UUID id, UUID borrowingId, LoanReminderKind kind, LocalDate dueDate, int seq,
                          LocalDate sentOn, UUID eventId) {
        this.tenantId = TenantContext.require();
        this.id = id;
        this.borrowingId = borrowingId;
        this.kind = kind;
        this.dueDate = dueDate;
        this.seq = seq;
        this.sentOn = sentOn;
        this.eventId = eventId;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    @Transient
    public boolean isNew() {
        return true;
    }

    UUID getBorrowingId() { return borrowingId; }
    LoanReminderKind getKind() { return kind; }
    LocalDate getDueDate() { return dueDate; }
    int getSeq() { return seq; }
    LocalDate getSentOn() { return sentOn; }
    UUID getEventId() { return eventId; }
}
