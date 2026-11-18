package lms.identity.tenantdata.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lms.identity.tenantdata.domain.TenantDataExport;
import lms.identity.tenantdata.domain.TenantDataExportException;
import lms.identity.tenantdata.domain.TenantDataExportException.Reason;
import lms.identity.tenantdata.domain.TenantDataExportRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Adapter cho tenant_data_exports (bảng toàn cục): lọc tenant_id tường minh ở mọi truy vấn theo tenant. */
@Repository
class TenantDataExportPersistenceAdapter implements TenantDataExportRepository {

    private final SpringDataTenantDataExportRepository repo;

    TenantDataExportPersistenceAdapter(SpringDataTenantDataExportRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional
    public void insert(TenantDataExport export) {
        try {
            repo.saveAndFlush(TenantDataExportJpaEntity.from(export));
        } catch (DataIntegrityViolationException e) {
            // chỉ mục duy nhất một phần: hai yêu cầu cùng lúc cho một tenant
            throw new TenantDataExportException(Reason.EXPORT_IN_PROGRESS, "Đã có một yêu cầu xuất đang xử lý");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TenantDataExport> find(UUID tenantId, UUID id) {
        return repo.findByIdAndTenantId(id, tenantId).map(TenantDataExportJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<TenantDataExport> findById(UUID id) {
        return repo.findById(id).map(TenantDataExportJpaEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TenantDataExport> listByTenant(UUID tenantId, int limit) {
        return repo.listByTenant(tenantId, PageRequest.of(0, limit)).stream()
                .map(TenantDataExportJpaEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasInProgress(UUID tenantId) {
        return repo.hasInProgress(tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasSuccessSince(UUID tenantId, Instant since) {
        return repo.hasSuccessSince(tenantId, since);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> pendingIds(int limit) {
        return repo.pendingIds(PageRequest.of(0, limit));
    }

    @Override
    @Transactional
    public boolean claim(UUID id, Instant now) {
        return repo.claim(id, now) == 1;
    }

    @Override
    @Transactional
    public void update(TenantDataExport export) {
        TenantDataExportJpaEntity row = repo.findById(export.id()).orElseThrow();
        row.apply(export);
        repo.saveAndFlush(row);
    }

    @Override
    @Transactional
    public int requeueRunning() {
        return repo.requeueRunning();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TenantDataExport> findRunningStartedBefore(Instant threshold) {
        return repo.runningStartedBefore(threshold).stream().map(TenantDataExportJpaEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<TenantDataExport> findReadyExpiredBefore(Instant now, int limit) {
        return repo.readyExpiredBefore(now, PageRequest.of(0, limit)).stream()
                .map(TenantDataExportJpaEntity::toDomain).toList();
    }

    @Override
    @Transactional
    public void recordDownload(UUID id, Instant now) {
        repo.recordDownload(id, now);
    }
}
