package lms.identity.apiclient.infrastructure.persistence;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lms.identity.apiclient.domain.Application;
import lms.identity.apiclient.domain.ApplicationException;
import lms.identity.apiclient.domain.ApplicationException.Reason;
import lms.identity.apiclient.domain.ApplicationKind;
import lms.identity.apiclient.domain.ApplicationRepository;
import lms.identity.apiclient.domain.ApplicationScope;
import lms.identity.apiclient.domain.ApplicationStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

/**
 * Bảng toàn cục (không RLS): MỌI truy vấn theo Application kèm tenantId; DELETED không bao giờ được trả về. Danh sách
 * nạp scope theo lô (một câu lệnh), không N+1.
 */
@Repository
class ApplicationPersistenceAdapter implements ApplicationRepository {

    /** Chỉ mục UNIQUE (tenant_id, lower(name)) trong V32. */
    static final String NAME_UNIQUE_INDEX = "uq_applications_tenant_name";
    private static final UUID NO_EXCLUDE = new UUID(0L, 0L);

    private final SpringDataApplicationRepository jpa;
    private final SpringDataApplicationScopeRepository scopes;

    ApplicationPersistenceAdapter(SpringDataApplicationRepository jpa, SpringDataApplicationScopeRepository scopes) {
        this.jpa = jpa;
        this.scopes = scopes;
    }

    @Override
    public Application save(Application a) {
        try {
            jpa.saveAndFlush(new ApplicationJpaEntity(a.id(), a.tenantId(), a.name(), a.kind().name(),
                    a.status().name(), a.siteKey(), a.rateLimitPerMinute(), a.createdAt(), a.createdBy(),
                    a.updatedAt(), a.updatedBy(), a.deletedAt(), a.version()));
        } catch (DataIntegrityViolationException ex) {
            if (violates(ex, NAME_UNIQUE_INDEX)) {
                throw new ApplicationException(Reason.APPLICATION_NAME_DUPLICATED, "Tên ứng dụng đã được dùng");
            }
            throw ex;
        }
        syncScopes(a);
        return a;
    }

    private void syncScopes(Application a) {
        Set<String> wanted = a.scopes().stream().map(ApplicationScope::code).collect(Collectors.toSet());
        List<ApplicationScopeJpaEntity> current = scopes.findByApplicationIds(List.of(a.id()));
        Set<String> existing = new HashSet<>();
        for (ApplicationScopeJpaEntity row : current) {
            if (wanted.contains(row.getScope())) {
                existing.add(row.getScope());
            } else {
                scopes.delete(row);
            }
        }
        for (String code : wanted) {
            if (!existing.contains(code)) {
                scopes.save(new ApplicationScopeJpaEntity(a.id(), code, a.updatedAt(), a.updatedBy()));
            }
        }
        scopes.flush();
    }

    @Override
    public Optional<Application> findVisibleById(UUID id) {
        return jpa.findById(id).filter(e -> !ApplicationStatus.DELETED.name().equals(e.getStatus()))
                .map(e -> toDomain(e, scopesOf(List.of(e.getId()))));
    }

    @Override
    public Optional<Application> findVisibleBySiteKey(String siteKey) {
        return jpa.findVisibleBySiteKey(siteKey).map(e -> toDomain(e, Map.of()));
    }

    @Override
    public Optional<Application> findByIdAndTenant(UUID id, UUID tenantId) {
        return jpa.findVisible(id, tenantId).map(e -> toDomain(e, scopesOf(List.of(e.getId()))));
    }

    @Override
    public Optional<Application> findByIdAndTenantForUpdate(UUID id, UUID tenantId) {
        return jpa.findVisibleForUpdate(id, tenantId).map(e -> toDomain(e, scopesOf(List.of(e.getId()))));
    }

    @Override
    public List<Application> findByTenant(UUID tenantId) {
        List<ApplicationJpaEntity> rows = jpa.findVisibleByTenant(tenantId);
        Map<UUID, Set<ApplicationScope>> byApp = scopesOf(rows.stream().map(ApplicationJpaEntity::getId).toList());
        return rows.stream().map(e -> toDomain(e, byApp)).toList();
    }

    @Override
    public int lockAndCountByTenant(UUID tenantId) {
        return jpa.lockVisibleByTenant(tenantId).size();
    }

    @Override
    public boolean existsName(UUID tenantId, String name, UUID excludeId) {
        return jpa.existsName(tenantId, name, excludeId == null ? NO_EXCLUDE : excludeId);
    }

    @Override
    public boolean existsSiteKey(String siteKey) {
        return jpa.existsBySiteKey(siteKey);
    }

    private Map<UUID, Set<ApplicationScope>> scopesOf(Collection<UUID> ids) {
        Map<UUID, Set<ApplicationScope>> result = new HashMap<>();
        if (ids.isEmpty()) return result;
        for (ApplicationScopeJpaEntity row : scopes.findByApplicationIds(ids)) {
            result.computeIfAbsent(row.getApplicationId(), k -> EnumSet.noneOf(ApplicationScope.class))
                    .add(ApplicationScope.parse(row.getScope()));
        }
        return result;
    }

    private static Application toDomain(ApplicationJpaEntity e, Map<UUID, Set<ApplicationScope>> scopesByApp) {
        return Application.restore(e.getId(), e.getTenantId(), e.getName(), ApplicationKind.valueOf(e.getKind()),
                ApplicationStatus.valueOf(e.getStatus()), e.getSiteKey(), e.getRateLimitPerMinute(),
                scopesByApp.getOrDefault(e.getId(), Set.of()), e.getCreatedAt(), e.getCreatedBy(), e.getUpdatedAt(),
                e.getUpdatedBy(), e.getDeletedAt(), e.getVersion());
    }

    private static boolean violates(DataIntegrityViolationException e, String constraint) {
        for (Throwable c = e; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (c instanceof org.hibernate.exception.ConstraintViolationException cve
                    && cve.getConstraintName() != null) {
                return cve.getConstraintName().toLowerCase().contains(constraint);
            }
            String message = c.getMessage();
            if (message != null && message.toLowerCase().contains(constraint)) return true;
        }
        return false;
    }
}
