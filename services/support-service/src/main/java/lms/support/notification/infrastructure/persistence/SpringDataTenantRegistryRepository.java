package lms.support.notification.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataTenantRegistryRepository extends JpaRepository<NotificationTenantJpaEntity, UUID> {

    /** Bảng notification_tenants không RLS (chỉ id tenant): chèn nếu chưa có. */
    @Modifying
    @Query(value = "INSERT INTO notification_tenants (tenant_id, first_seen_at) VALUES (:tenantId, :now) "
            + "ON CONFLICT (tenant_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("tenantId") UUID tenantId, @Param("now") Instant now);
}
