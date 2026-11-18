package lms.circulation.policy.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lms.tenant.TenantContext;
import org.hibernate.annotations.TenantId;

/**
 * Chính sách mượn trả, một dòng mỗi tenant: tenant_id do Hibernate điền/lọc (@TenantId), RLS lọc thêm ở DB
 * (migration circulation V8). Khóa lạc quan bằng cột version.
 */
@Entity
@Table(name = "circulation_policies")
class CirculationPolicyJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "loan_days", nullable = false)
    private int loanDays;
    @Column(name = "max_renewals", nullable = false)
    private int maxRenewals;
    @Column(name = "renewal_days", nullable = false)
    private int renewalDays;
    @Column(name = "max_active_loans_per_reader", nullable = false)
    private int maxActiveLoansPerReader;
    @Column(name = "fine_per_day", nullable = false)
    private long finePerDay;
    @Column(name = "grace_days", nullable = false)
    private int graceDays;
    @Column(name = "reservation_hold_days", nullable = false)
    private int reservationHoldDays;
    @Column(name = "due_reminder_days_before", nullable = false)
    private int dueReminderDaysBefore;
    @Column(name = "overdue_reminder_interval_days", nullable = false)
    private int overdueReminderIntervalDays;
    @Column(name = "overdue_reminder_max", nullable = false)
    private int overdueReminderMax;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "updated_by")
    private UUID updatedBy;

    protected CirculationPolicyJpaEntity() {
    }

    /** Dòng mới: tenant lấy từ TenantContext (không có thì TenantRequiredException, fail-closed). */
    CirculationPolicyJpaEntity(UUID id) {
        this.tenantId = TenantContext.require();
        this.id = id;
    }

    void apply(int loanDays, int maxRenewals, int renewalDays, int maxActiveLoansPerReader, long finePerDay,
               int graceDays, int reservationHoldDays, int dueReminderDaysBefore, int overdueReminderIntervalDays,
               int overdueReminderMax, Instant updatedAt, UUID updatedBy) {
        this.loanDays = loanDays;
        this.maxRenewals = maxRenewals;
        this.renewalDays = renewalDays;
        this.maxActiveLoansPerReader = maxActiveLoansPerReader;
        this.finePerDay = finePerDay;
        this.graceDays = graceDays;
        this.reservationHoldDays = reservationHoldDays;
        this.dueReminderDaysBefore = dueReminderDaysBefore;
        this.overdueReminderIntervalDays = overdueReminderIntervalDays;
        this.overdueReminderMax = overdueReminderMax;
        this.updatedAt = updatedAt;
        this.updatedBy = updatedBy;
    }

    long getVersion() { return version == null ? 0 : version; }
    int getLoanDays() { return loanDays; }
    int getMaxRenewals() { return maxRenewals; }
    int getRenewalDays() { return renewalDays; }
    int getMaxActiveLoansPerReader() { return maxActiveLoansPerReader; }
    long getFinePerDay() { return finePerDay; }
    int getGraceDays() { return graceDays; }
    int getReservationHoldDays() { return reservationHoldDays; }
    int getDueReminderDaysBefore() { return dueReminderDaysBefore; }
    int getOverdueReminderIntervalDays() { return overdueReminderIntervalDays; }
    int getOverdueReminderMax() { return overdueReminderMax; }
    Instant getUpdatedAt() { return updatedAt; }
    UUID getUpdatedBy() { return updatedBy; }
}
