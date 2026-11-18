package lms.identity.tenantdata.infrastructure.persistence;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lms.identity.tenantdata.domain.ExportPart;
import lms.identity.tenantdata.domain.ExportPartRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Adapter cho tenant_data_export_parts. */
@Repository
class ExportPartPersistenceAdapter implements ExportPartRepository {

    private final SpringDataExportPartRepository repo;

    ExportPartPersistenceAdapter(SpringDataExportPartRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional
    public void save(ExportPart part) {
        ExportPartJpaEntity row = repo.findById(new ExportPartJpaEntity.Key(part.exportId(), part.service()))
                .orElseGet(() -> ExportPartJpaEntity.from(part));
        row.apply(part);
        repo.saveAndFlush(row);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExportPart> findByExport(UUID exportId) {
        return repo.findByExport(exportId).stream().map(ExportPartJpaEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<ExportPart>> findByExports(Collection<UUID> exportIds) {
        if (exportIds.isEmpty()) {
            return Map.of();
        }
        return repo.findByExports(exportIds).stream().map(ExportPartJpaEntity::toDomain)
                .collect(Collectors.groupingBy(ExportPart::exportId, LinkedHashMap::new, Collectors.toList()));
    }

    @Override
    @Transactional
    public void deleteByExport(UUID exportId) {
        repo.deleteByExport(exportId);
    }
}
