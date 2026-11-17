package lms.identity.tenant.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataStorefrontOriginRepository extends JpaRepository<TenantStorefrontOriginJpaEntity, UUID> {

    @Query("select o from TenantStorefrontOriginJpaEntity o where o.tenantId = :tenantId order by o.createdAt, o.id")
    List<TenantStorefrontOriginJpaEntity> findByTenant(@Param("tenantId") UUID tenantId);

    @Query("select o.origin from TenantStorefrontOriginJpaEntity o where o.tenantId = :tenantId and o.enabled = true "
            + "order by o.createdAt, o.id")
    List<String> findEnabledOrigins(@Param("tenantId") UUID tenantId);

    @Query("select o.tenantId from TenantStorefrontOriginJpaEntity o where o.origin = :origin")
    Optional<UUID> findOwnerTenant(@Param("origin") String origin);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from TenantStorefrontOriginJpaEntity o where o.id = :id")
    void deleteByOriginId(@Param("id") UUID id);
}
