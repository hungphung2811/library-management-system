package lms.identity.tenant.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.identity.tenant.domain.DuplicateTenantException;
import lms.identity.tenant.domain.Tenant;
import lms.identity.tenant.domain.TenantRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
class TenantPersistenceAdapter implements TenantRepository {

    private final SpringDataTenantRepository jpa;

    TenantPersistenceAdapter(SpringDataTenantRepository jpa) {
        this.jpa = jpa;
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

    private static Tenant toDomain(TenantJpaEntity e) {
        return Tenant.restore(e.getId(), e.getCode(), e.getName(), e.getOrganizationId(), e.getStatus(),
                e.getCreatedAt());
    }
}
