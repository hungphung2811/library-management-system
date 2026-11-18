package lms.identity.tenantdata;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lms.identity.tenantdata.domain.ExportPart;
import lms.identity.tenantdata.domain.ExportPartRepository;
import lms.identity.tenantdata.domain.ExportStatus;
import lms.identity.tenantdata.domain.TenantDataExport;
import lms.identity.tenantdata.domain.TenantDataExportException;
import lms.identity.tenantdata.domain.TenantDataExportException.Reason;
import lms.identity.tenantdata.domain.TenantDataExportRepository;

/** Repository trong bộ nhớ cho test đơn vị (mô phỏng chỉ mục duy nhất một phần và claim có điều kiện). */
final class InMemoryRepos {

    private InMemoryRepos() {
    }

    static final class Exports implements TenantDataExportRepository {

        final Map<UUID, TenantDataExport> rows = new LinkedHashMap<>();
        final Map<UUID, Integer> downloads = new LinkedHashMap<>();

        @Override
        public void insert(TenantDataExport e) {
            if (rows.values().stream().anyMatch(r -> r.tenantId().equals(e.tenantId()) && r.status().inProgress())) {
                throw new TenantDataExportException(Reason.EXPORT_IN_PROGRESS, "x");
            }
            rows.put(e.id(), copy(e));
        }

        @Override
        public Optional<TenantDataExport> find(UUID tenantId, UUID id) {
            return Optional.ofNullable(rows.get(id)).filter(e -> e.tenantId().equals(tenantId)).map(Exports::copy);
        }

        @Override
        public Optional<TenantDataExport> findById(UUID id) {
            return Optional.ofNullable(rows.get(id)).map(Exports::copy);
        }

        @Override
        public List<TenantDataExport> listByTenant(UUID tenantId, int limit) {
            return rows.values().stream().filter(e -> e.tenantId().equals(tenantId))
                    .sorted(Comparator.comparing(TenantDataExport::createdAt).reversed()).limit(limit)
                    .map(Exports::copy).toList();
        }

        @Override
        public boolean hasInProgress(UUID tenantId) {
            return rows.values().stream().anyMatch(e -> e.tenantId().equals(tenantId) && e.status().inProgress());
        }

        @Override
        public boolean hasSuccessSince(UUID tenantId, Instant since) {
            return rows.values().stream().anyMatch(e -> e.tenantId().equals(tenantId) && e.succeededSince(since));
        }

        @Override
        public List<UUID> pendingIds(int limit) {
            return rows.values().stream().filter(e -> e.status() == ExportStatus.PENDING)
                    .sorted(Comparator.comparing(TenantDataExport::createdAt)).limit(limit)
                    .map(TenantDataExport::id).toList();
        }

        @Override
        public boolean claim(UUID id, Instant now) {
            TenantDataExport e = rows.get(id);
            if (e == null || e.status() != ExportStatus.PENDING) {
                return false;
            }
            TenantDataExport running = TenantDataExport.restore(e.id(), e.tenantId(), ExportStatus.RUNNING,
                    e.requestedBy(), e.requestedByPlatform(), e.reason(), e.createdAt(), now, null, null, 0, 0, 0,
                    null, null, null, 0, null);
            rows.put(id, running);
            return true;
        }

        @Override
        public void update(TenantDataExport e) {
            rows.put(e.id(), copy(e));
        }

        @Override
        public int requeueRunning() {
            int n = 0;
            for (TenantDataExport e : new ArrayList<>(rows.values())) {
                if (e.status() == ExportStatus.RUNNING) {
                    rows.put(e.id(), TenantDataExport.restore(e.id(), e.tenantId(), ExportStatus.PENDING,
                            e.requestedBy(), e.requestedByPlatform(), e.reason(), e.createdAt(), null, null, null,
                            0, 0, 0, null, null, null, 0, null));
                    n++;
                }
            }
            return n;
        }

        @Override
        public List<TenantDataExport> findRunningStartedBefore(Instant threshold) {
            return rows.values().stream().filter(e -> e.status() == ExportStatus.RUNNING
                    && e.startedAt() != null && e.startedAt().isBefore(threshold)).map(Exports::copy).toList();
        }

        @Override
        public List<TenantDataExport> findReadyExpiredBefore(Instant now, int limit) {
            return rows.values().stream().filter(e -> e.status() == ExportStatus.READY && e.expiresAt() != null
                    && !e.expiresAt().isAfter(now)).limit(limit).map(Exports::copy).toList();
        }

        @Override
        public void recordDownload(UUID id, Instant now) {
            downloads.merge(id, 1, Integer::sum);
        }

        static TenantDataExport copy(TenantDataExport e) {
            return TenantDataExport.restore(e.id(), e.tenantId(), e.status(), e.requestedBy(),
                    e.requestedByPlatform(), e.reason(), e.createdAt(), e.startedAt(), e.finishedAt(), e.expiresAt(),
                    e.partsTotal(), e.partsDone(), e.sizeBytes(), e.sha256(), e.storageKey(), e.errorCode(),
                    e.downloadCount(), e.lastDownloadedAt());
        }
    }

    static final class Parts implements ExportPartRepository {

        final Map<String, ExportPart> rows = new LinkedHashMap<>();

        private static String k(UUID exportId, String service) {
            return exportId + "/" + service;
        }

        @Override
        public void save(ExportPart p) {
            rows.put(k(p.exportId(), p.service()), ExportPart.restore(p.exportId(), p.service(), p.status(),
                    p.attempts(), p.tables(), p.errorCode(), p.finishedAt()));
        }

        @Override
        public List<ExportPart> findByExport(UUID exportId) {
            return rows.values().stream().filter(p -> p.exportId().equals(exportId)).toList();
        }

        @Override
        public Map<UUID, List<ExportPart>> findByExports(Collection<UUID> ids) {
            return rows.values().stream().filter(p -> ids.contains(p.exportId()))
                    .collect(Collectors.groupingBy(ExportPart::exportId));
        }

        @Override
        public void deleteByExport(UUID exportId) {
            rows.keySet().removeIf(key -> key.startsWith(exportId + "/"));
        }
    }
}
