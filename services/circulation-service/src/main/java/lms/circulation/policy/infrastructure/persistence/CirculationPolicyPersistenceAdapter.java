package lms.circulation.policy.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lms.circulation.policy.domain.CirculationPolicy;
import lms.circulation.policy.domain.CirculationPolicyRepository;
import lms.circulation.policy.domain.CirculationPolicySnapshot;
import lms.circulation.policy.domain.PolicyVersionConflictException;
import lms.tenant.TenantContext;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
class CirculationPolicyPersistenceAdapter implements CirculationPolicyRepository {

    private final SpringDataCirculationPolicyRepository jpa;

    CirculationPolicyPersistenceAdapter(SpringDataCirculationPolicyRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<CirculationPolicySnapshot> find() {
        if (TenantContext.current().isEmpty()) {
            return Optional.empty();
        }
        return jpa.findAll().stream().findFirst().map(CirculationPolicyPersistenceAdapter::toSnapshot);
    }

    @Override
    public CirculationPolicySnapshot save(CirculationPolicy p, UUID updatedBy, Instant now) {
        CirculationPolicyJpaEntity entity = jpa.findAll().stream().findFirst()
                .orElseGet(() -> new CirculationPolicyJpaEntity(UUID.randomUUID()));
        entity.apply(p.loanDays(), p.maxRenewals(), p.renewalDays(), p.maxActiveLoansPerReader(), p.finePerDay(),
                p.graceDays(), p.reservationHoldDays(), p.dueReminderDaysBefore(), p.overdueReminderIntervalDays(),
                p.overdueReminderMax(), now, updatedBy);
        try {
            return toSnapshot(jpa.saveAndFlush(entity));
        } catch (OptimisticLockingFailureException e) {
            throw new PolicyVersionConflictException("Chính sách vừa được người khác cập nhật, hãy tải lại rồi sửa");
        }
    }

    /**
     * Version báo ra cho client = cột version + 1: chưa có dòng là 0 và lần lưu đầu thành 1, nên người sửa lần đầu với
     * version 0 không ghi đè được người khác đã lưu trước (nếu dùng thẳng cột version, dòng mới cũng mang 0).
     */
    private static CirculationPolicySnapshot toSnapshot(CirculationPolicyJpaEntity e) {
        return new CirculationPolicySnapshot(new CirculationPolicy(e.getLoanDays(), e.getMaxRenewals(),
                e.getRenewalDays(), e.getMaxActiveLoansPerReader(), e.getFinePerDay(), e.getGraceDays(),
                e.getReservationHoldDays(), e.getDueReminderDaysBefore(), e.getOverdueReminderIntervalDays(),
                e.getOverdueReminderMax()), e.getVersion() + 1, e.getUpdatedAt(), e.getUpdatedBy());
    }
}
