package lms.identity.tenant.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataTenantModuleRepository extends JpaRepository<TenantModuleJpaEntity, TenantModuleId> {

    @Query("select m from TenantModuleJpaEntity m where m.tenantId = :tenantId")
    List<TenantModuleJpaEntity> findByTenant(@Param("tenantId") UUID tenantId);

    @Query("select m from TenantModuleJpaEntity m where m.tenantId in :tenantIds and m.enabled = true")
    List<TenantModuleJpaEntity> findEnabledByTenants(@Param("tenantIds") Collection<UUID> tenantIds);
}
