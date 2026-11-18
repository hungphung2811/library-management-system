package lms.identity.tenantdata.infrastructure.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataTenantDataExportRepository extends JpaRepository<TenantDataExportJpaEntity, UUID> {

    Optional<TenantDataExportJpaEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    @Query("select e from TenantDataExportJpaEntity e where e.tenantId = :tenantId order by e.createdAt desc")
    List<TenantDataExportJpaEntity> listByTenant(@Param("tenantId") UUID tenantId, Pageable page);

    @Query("select count(e) > 0 from TenantDataExportJpaEntity e where e.tenantId = :tenantId "
            + "and e.status in ('PENDING', 'RUNNING')")
    boolean hasInProgress(@Param("tenantId") UUID tenantId);

    @Query("select count(e) > 0 from TenantDataExportJpaEntity e where e.tenantId = :tenantId "
            + "and e.status in ('READY', 'EXPIRED') and e.finishedAt > :since")
    boolean hasSuccessSince(@Param("tenantId") UUID tenantId, @Param("since") Instant since);

    @Query("select e.id from TenantDataExportJpaEntity e where e.status = 'PENDING' order by e.createdAt asc")
    List<UUID> pendingIds(Pageable page);

    @Modifying
    @Query("update TenantDataExportJpaEntity e set e.status = 'RUNNING', e.startedAt = :now "
            + "where e.id = :id and e.status = 'PENDING'")
    int claim(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("update TenantDataExportJpaEntity e set e.status = 'PENDING', e.startedAt = null, e.partsDone = 0 "
            + "where e.status = 'RUNNING'")
    int requeueRunning();

    @Query("select e from TenantDataExportJpaEntity e where e.status = 'RUNNING' and e.startedAt < :threshold")
    List<TenantDataExportJpaEntity> runningStartedBefore(@Param("threshold") Instant threshold);

    @Query("select e from TenantDataExportJpaEntity e where e.status = 'READY' and e.expiresAt <= :now "
            + "order by e.expiresAt asc")
    List<TenantDataExportJpaEntity> readyExpiredBefore(@Param("now") Instant now, Pageable page);

    @Modifying
    @Query("update TenantDataExportJpaEntity e set e.downloadCount = e.downloadCount + 1, "
            + "e.lastDownloadedAt = :now where e.id = :id")
    int recordDownload(@Param("id") UUID id, @Param("now") Instant now);

    @Query("select e from TenantDataExportJpaEntity e where e.id in :ids")
    List<TenantDataExportJpaEntity> byIds(@Param("ids") Collection<UUID> ids);
}
