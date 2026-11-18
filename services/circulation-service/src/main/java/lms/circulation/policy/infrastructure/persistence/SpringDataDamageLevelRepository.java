package lms.circulation.policy.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Hibernate tự lọc theo tenant (@TenantId); RLS lọc thêm ở DB. Index (tenant_id, sort_order, code) phục vụ findAllOrdered. */
interface SpringDataDamageLevelRepository extends JpaRepository<DamageLevelJpaEntity, UUID> {

    @Query("select d from DamageLevelJpaEntity d order by d.sortOrder, d.code")
    List<DamageLevelJpaEntity> findAllOrdered();

    @Query("select d from DamageLevelJpaEntity d where d.code = :code")
    Optional<DamageLevelJpaEntity> findInTenantByCode(@Param("code") String code);
}
