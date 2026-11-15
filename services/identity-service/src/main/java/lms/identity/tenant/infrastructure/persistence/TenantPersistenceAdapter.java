package lms.identity.tenant.infrastructure.persistence;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lms.identity.tenant.domain.DuplicateTenantException;
import lms.identity.tenant.domain.MembershipStatus;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantCriteria;
import lms.identity.tenant.domain.TenantPage;
import lms.identity.tenant.domain.TenantQueryRepository;
import lms.identity.tenant.domain.TenantRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

@Repository
class TenantPersistenceAdapter implements TenantRepository, TenantQueryRepository {

    private final SpringDataTenantRepository jpa;
    private final SpringDataMembershipRepository memberships;

    TenantPersistenceAdapter(SpringDataTenantRepository jpa, SpringDataMembershipRepository memberships) {
        this.jpa = jpa;
        this.memberships = memberships;
    }

    @Override
    public Tenant save(Tenant t) {
        try {
            jpa.saveAndFlush(new TenantJpaEntity(t.id(), t.code(), t.name(), t.organizationId(), t.status(),
                    t.createdAt()));
        } catch (DataIntegrityViolationException e) {
            // unique(code): hai yêu cầu tạo cùng mã đồng thời
            throw new DuplicateTenantException("Mã tenant đã tồn tại: " + t.code());
        }
        return t;
    }

    @Override
    public Optional<Tenant> findById(UUID id) {
        return jpa.findById(id).map(TenantPersistenceAdapter::toDomain);
    }

    @Override
    public Optional<Tenant> findByCode(String code) {
        return jpa.findByCode(code).map(TenantPersistenceAdapter::toDomain);
    }

    @Override
    public List<Tenant> findAll() {
        return jpa.findAllByOrderByCode().stream().map(TenantPersistenceAdapter::toDomain).toList();
    }

    @Override
    public TenantPage search(TenantCriteria c) {
        Specification<TenantJpaEntity> spec = (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (c.keyword() != null) {
                // ký tự đại diện của LIKE trong từ khóa được thoát bằng '!'
                String like = "%" + c.keyword().toLowerCase().replace("!", "!!").replace("%", "!%")
                        .replace("_", "!_") + "%";
                where.add(cb.or(cb.like(cb.lower(root.get("code")), like, '!'),
                        cb.like(cb.lower(root.get("name")), like, '!')));
            }
            if (c.status() != null) where.add(cb.equal(root.get("status"), c.status()));
            if (c.organizationId() != null) where.add(cb.equal(root.get("organizationId"), c.organizationId()));
            return cb.and(where.toArray(Predicate[]::new));
        };
        Page<TenantJpaEntity> page = jpa.findAll(spec, PageRequest.of(c.page(), c.size(), Sort.by("code")));
        return new TenantPage(page.getContent().stream().map(TenantPersistenceAdapter::toDomain).toList(),
                page.getTotalElements());
    }

    @Override
    public Map<UUID, Long> countActiveMembers(Collection<UUID> tenantIds) {
        Map<UUID, Long> counts = new HashMap<>();
        if (tenantIds.isEmpty()) return counts;
        for (Object[] row : memberships.countByTenant(tenantIds, MembershipStatus.ACTIVE)) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    private static Tenant toDomain(TenantJpaEntity e) {
        return Tenant.restore(e.getId(), e.getCode(), e.getName(), e.getOrganizationId(), e.getStatus(),
                e.getCreatedAt());
    }
}
