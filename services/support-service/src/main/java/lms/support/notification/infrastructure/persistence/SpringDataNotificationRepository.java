package lms.support.notification.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataNotificationRepository extends JpaRepository<NotificationJpaEntity, UUID> {

    /** Hibernate (@TenantId) tự lọc theo TenantContext, RLS lọc thêm ở DB. */
    List<NotificationJpaEntity> findByUserIdOrderByCreatedAtDescIdAsc(UUID userId);

    /**
     * INSERT ... ON CONFLICT DO NOTHING: idempotent theo (tenant_id, event_id), an toàn khi hai consumer trùng event.
     * tenant_id điền tay (native SQL không qua @TenantId); RLS WITH CHECK từ chối nếu khác tenant của phiên.
     */
    @Modifying
    @Query(value = """
            INSERT INTO notifications (id, tenant_id, event_id, user_id, type, message, created_at, read_at)
            VALUES (:id, :tenantId, :eventId, :userId, :type, :message, :createdAt, :readAt)
            ON CONFLICT (tenant_id, event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("tenantId") UUID tenantId, @Param("eventId") UUID eventId,
                       @Param("userId") UUID userId, @Param("type") String type, @Param("message") String message,
                       @Param("createdAt") Instant createdAt, @Param("readAt") Instant readAt);
}
