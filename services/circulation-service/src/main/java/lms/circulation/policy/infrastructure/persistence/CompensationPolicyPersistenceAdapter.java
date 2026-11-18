package lms.circulation.policy.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lms.circulation.policy.domain.CompensationPolicy;
import lms.circulation.policy.domain.CompensationPolicyRepository;
import lms.circulation.policy.domain.CompensationPolicySnapshot;
import lms.circulation.policy.domain.PolicyVersionConflictException;
import lms.tenant.TenantContext;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
class CompensationPolicyPersistenceAdapter implements CompensationPolicyRepository {

    private final SpringDataCompensationPolicyRepository jpa;

    CompensationPolicyPersistenceAdapter(SpringDataCompensationPolicyRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<CompensationPolicySnapshot> find() {
        if (TenantContext.current().isEmpty()) {
            return Optional.empty();
        }
        return jpa.findAll().stream().findFirst().map(CompensationPolicyPersistenceAdapter::toSnapshot);
    }

    @Override
    public CompensationPolicySnapshot save(CompensationPolicy p, UUID updatedBy, Instant now) {
        CompensationPolicyJpaEntity entity = jpa.findAll().stream().findFirst()
                .orElseGet(() -> new CompensationPolicyJpaEntity(UUID.randomUUID()));
        entity.apply(p.lostRate(), p.priceSource(), p.minAmount(), p.maxAmount(), p.foundWindowDays(),
                p.refundProcessingFee(), now, updatedBy);
        try {
            return toSnapshot(jpa.saveAndFlush(entity));
        } catch (OptimisticLockingFailureException e) {
            throw new PolicyVersionConflictException("Chính sách đền bù vừa được người khác cập nhật, hãy tải lại rồi sửa");
        }
    }

    /** Version báo ra cho client = cột version + 1 (như circulation_policies): chưa có dòng là 0, lần lưu đầu thành 1. */
    private static CompensationPolicySnapshot toSnapshot(CompensationPolicyJpaEntity e) {
        return new CompensationPolicySnapshot(new CompensationPolicy(e.getLostRate(), e.getPriceSource(),
                e.getMinAmount(), e.getMaxAmount(), e.getFoundWindowDays(), e.getRefundProcessingFee()),
                e.getVersion() + 1, e.getUpdatedAt(), e.getUpdatedBy());
    }
}
