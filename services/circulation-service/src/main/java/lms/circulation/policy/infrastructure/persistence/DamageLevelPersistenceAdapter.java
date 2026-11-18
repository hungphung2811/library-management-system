package lms.circulation.policy.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.circulation.policy.domain.CompensationRuleException;
import lms.circulation.policy.domain.DamageLevel;
import lms.circulation.policy.domain.DamageLevelRepository;
import lms.circulation.policy.domain.DamageLevelSnapshot;
import lms.circulation.policy.domain.PolicyVersionConflictException;
import lms.tenant.TenantContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;

@Repository
class DamageLevelPersistenceAdapter implements DamageLevelRepository {

    private final SpringDataDamageLevelRepository jpa;

    DamageLevelPersistenceAdapter(SpringDataDamageLevelRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public List<DamageLevelSnapshot> findAll() {
        if (TenantContext.current().isEmpty()) {
            return List.of();
        }
        return jpa.findAllOrdered().stream().map(DamageLevelPersistenceAdapter::toSnapshot).toList();
    }

    @Override
    public Optional<DamageLevelSnapshot> findByCode(String code) {
        return jpa.findInTenantByCode(code).map(DamageLevelPersistenceAdapter::toSnapshot);
    }

    @Override
    public long count() {
        return jpa.count();
    }

    @Override
    public DamageLevelSnapshot create(DamageLevel level, UUID updatedBy, Instant now) {
        DamageLevelJpaEntity entity = new DamageLevelJpaEntity(UUID.randomUUID(), level.code(), now);
        entity.apply(level.label(), level.rate(), level.sortOrder(), level.active(), now, updatedBy);
        try {
            return toSnapshot(jpa.saveAndFlush(entity));
        } catch (DataIntegrityViolationException e) {
            // uq_damage_levels_tenant_code: hai quản trị tạo cùng mã đồng thời
            throw CompensationRuleException.conflict("DAMAGE_LEVEL_CODE_EXISTS",
                    "Mã bậc hỏng đã tồn tại trong đơn vị: " + level.code());
        }
    }

    @Override
    public DamageLevelSnapshot update(DamageLevel level, long expectedVersion, UUID updatedBy, Instant now) {
        DamageLevelJpaEntity entity = jpa.findInTenantByCode(level.code()).orElseThrow(() -> CompensationRuleException
                .notFound("DAMAGE_LEVEL_NOT_FOUND", "Không tìm thấy bậc hỏng: " + level.code()));
        if (entity.getVersion() != expectedVersion) {
            throw new PolicyVersionConflictException("Bậc hỏng vừa được người khác cập nhật, hãy tải lại rồi sửa");
        }
        entity.apply(level.label(), level.rate(), level.sortOrder(), level.active(), now, updatedBy);
        try {
            return toSnapshot(jpa.saveAndFlush(entity));
        } catch (OptimisticLockingFailureException e) {
            throw new PolicyVersionConflictException("Bậc hỏng vừa được người khác cập nhật, hãy tải lại rồi sửa");
        }
    }

    @Override
    public void delete(String code) {
        jpa.findInTenantByCode(code).ifPresent(jpa::delete);
    }

    private static DamageLevelSnapshot toSnapshot(DamageLevelJpaEntity e) {
        return new DamageLevelSnapshot(new DamageLevel(e.getCode(), e.getLabel(), e.getRate(), e.getSortOrder(),
                e.isActive()), e.getVersion(), e.getCreatedAt(), e.getUpdatedAt(), e.getUpdatedBy());
    }
}
