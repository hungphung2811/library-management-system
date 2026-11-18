package lms.identity.apiclient.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataApplicationRepository extends JpaRepository<ApplicationJpaEntity, UUID> {

    @Query("select a from ApplicationJpaEntity a where a.id = :id and a.tenantId = :tenantId and a.status <> 'DELETED'")
    Optional<ApplicationJpaEntity> findVisible(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ApplicationJpaEntity a where a.id = :id and a.tenantId = :tenantId and a.status <> 'DELETED'")
    Optional<ApplicationJpaEntity> findVisibleForUpdate(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    @Query("select a from ApplicationJpaEntity a where a.tenantId = :tenantId and a.status <> 'DELETED' "
            + "order by a.createdAt, a.id")
    List<ApplicationJpaEntity> findVisibleByTenant(@Param("tenantId") UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ApplicationJpaEntity a where a.tenantId = :tenantId and a.status <> 'DELETED' "
            + "order by a.createdAt, a.id")
    List<ApplicationJpaEntity> lockVisibleByTenant(@Param("tenantId") UUID tenantId);

    @Query("select count(a) > 0 from ApplicationJpaEntity a where a.tenantId = :tenantId "
            + "and lower(a.name) = lower(:name) and a.status <> 'DELETED' and a.id <> :excludeId")
    boolean existsName(@Param("tenantId") UUID tenantId, @Param("name") String name,
                       @Param("excludeId") UUID excludeId);

    boolean existsBySiteKey(String siteKey);
}
