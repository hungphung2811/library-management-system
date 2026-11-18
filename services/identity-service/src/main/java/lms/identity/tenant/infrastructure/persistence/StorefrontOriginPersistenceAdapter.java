package lms.identity.tenant.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.identity.tenant.domain.StorefrontOriginDuplicatedException;
import lms.identity.tenant.domain.StorefrontOriginEntry;
import lms.identity.tenant.domain.StorefrontOriginHistoryEntry;
import lms.identity.tenant.domain.StorefrontOriginRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

@Repository
class StorefrontOriginPersistenceAdapter implements StorefrontOriginRepository {

    /** UNIQUE(origin) trong V21. */
    static final String ORIGIN_UNIQUE_CONSTRAINT = "tenant_storefront_origins_origin_key";

    private final SpringDataStorefrontOriginRepository jpa;
    private final SpringDataStorefrontOriginHistoryRepository history;

    StorefrontOriginPersistenceAdapter(SpringDataStorefrontOriginRepository jpa,
                                       SpringDataStorefrontOriginHistoryRepository history) {
        this.jpa = jpa;
        this.history = history;
    }

    @Override
    public List<StorefrontOriginEntry> findByTenant(UUID tenantId) {
        return jpa.findByTenant(tenantId).stream().map(StorefrontOriginPersistenceAdapter::toDomain).toList();
    }

    @Override
    public List<String> findEnabledOrigins(UUID tenantId) {
        return jpa.findEnabledOrigins(tenantId);
    }

    @Override
    public List<String> findEnabledOriginsOfApplication(UUID tenantId, UUID applicationId) {
        return jpa.findEnabledOriginsOfApplication(tenantId, applicationId);
    }

    @Override
    public Optional<UUID> findOwnerTenant(String origin) {
        return jpa.findOwnerTenant(origin);
    }

    @Override
    public void insert(StorefrontOriginEntry e) {
        try {
            jpa.saveAndFlush(toEntity(e));
        } catch (DataIntegrityViolationException ex) {
            if (isOriginUniqueViolation(ex)) {
                throw new StorefrontOriginDuplicatedException("Origin đã tồn tại: " + e.origin(), null);
            }
            throw ex;
        }
    }

    @Override
    public void update(StorefrontOriginEntry e) {
        jpa.saveAndFlush(toEntity(e));
    }

    @Override
    public void delete(UUID originId) {
        jpa.deleteByOriginId(originId);
    }

    @Override
    public void appendHistory(StorefrontOriginHistoryEntry h) {
        history.saveAndFlush(new TenantStorefrontOriginHistoryJpaEntity(h.id(), h.tenantId(), h.originId(),
                h.origin(), h.action().name(), h.actorId(), h.occurredAt()));
    }

    private static boolean isOriginUniqueViolation(DataIntegrityViolationException e) {
        for (Throwable c = e; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof org.hibernate.exception.ConstraintViolationException cve
                    && cve.getConstraintName() != null) {
                return cve.getConstraintName().toLowerCase().contains(ORIGIN_UNIQUE_CONSTRAINT);
            }
            String message = c.getMessage();
            if (message != null && message.toLowerCase().contains(ORIGIN_UNIQUE_CONSTRAINT)) return true;
        }
        return false;
    }

    private static TenantStorefrontOriginJpaEntity toEntity(StorefrontOriginEntry e) {
        return new TenantStorefrontOriginJpaEntity(e.id(), e.tenantId(), e.origin(), e.enabled(), e.createdAt(),
                e.createdBy(), e.updatedAt(), e.updatedBy(), e.applicationId());
    }

    private static StorefrontOriginEntry toDomain(TenantStorefrontOriginJpaEntity e) {
        return new StorefrontOriginEntry(e.getId(), e.getTenantId(), e.getOrigin(), e.isEnabled(), e.getCreatedAt(),
                e.getCreatedBy(), e.getUpdatedAt(), e.getUpdatedBy(), e.getApplicationId());
    }
}
