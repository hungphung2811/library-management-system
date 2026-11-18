package lms.support.notification.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lms.support.notification.domain.DeliveryStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface SpringDataDeliveryRepository
        extends JpaRepository<NotificationDeliveryJpaEntity, UUID>, JpaSpecificationExecutor<NotificationDeliveryJpaEntity> {

    /**
     * Khóa hàng đến hạn và bỏ qua dòng instance khác đang khóa (hint lock.timeout = -2 = SKIP LOCKED; IT xác nhận câu
     * SQL). Hibernate (@TenantId) lọc theo tenant, RLS lọc thêm ở DB.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select d from NotificationDeliveryJpaEntity d where d.status in :statuses and d.nextAttemptAt <= :now "
            + "order by d.nextAttemptAt asc")
    List<NotificationDeliveryJpaEntity> lockDue(@Param("statuses") Collection<DeliveryStatus> statuses,
                                                @Param("now") Instant now, Pageable pageable);

    long countByStatusAndSentAtGreaterThanEqual(DeliveryStatus status, Instant since);

    List<NotificationDeliveryJpaEntity> findByRecipientUserIdOrderByCreatedAtDescIdAsc(UUID userId, Pageable pageable);

    /** HQL: Hibernate thêm điều kiện @TenantId cho cả câu UPDATE; RLS lọc thêm ở DB. */
    @Modifying
    @Query("update NotificationDeliveryJpaEntity d set d.status = :to, d.nextAttemptAt = :now, d.updatedAt = :now "
            + "where d.status = :from and d.updatedAt < :staleBefore")
    int releaseStuck(@Param("from") DeliveryStatus from, @Param("to") DeliveryStatus to,
                     @Param("staleBefore") Instant staleBefore, @Param("now") Instant now);

    @Modifying
    @Query("delete from NotificationDeliveryJpaEntity d where d.status in :terminal and d.updatedAt < :before")
    int deleteFinishedBefore(@Param("terminal") Collection<DeliveryStatus> terminal, @Param("before") Instant before);

    /**
     * INSERT ... ON CONFLICT DO NOTHING theo khóa khử trùng (tenant_id, event_id, channel, recipient_key): Kafka giao lại
     * hoặc hai instance cùng nhận đều chỉ ra một dòng. tenant_id điền tay (native không qua @TenantId); RLS WITH CHECK
     * từ chối nếu khác tenant của phiên.
     */
    @Modifying
    @Query(value = """
            INSERT INTO notification_deliveries (id, tenant_id, event_id, event_type, notification_type, category,
                channel, recipient_user_id, recipient_key, recipient_address, recipient_address_masked, params, status,
                skip_reason, attempts, attempts_base, next_attempt_at, created_at, updated_at)
            VALUES (:id, :tenantId, :eventId, :eventType, :type, :category, :channel, CAST(:userId AS uuid), :recipientKey, CAST(:address AS varchar),
                CAST(:masked AS varchar), CAST(:params AS jsonb), :status, CAST(:skipReason AS varchar), 0, 0, :nextAttemptAt, :now, :now)
            ON CONFLICT (tenant_id, event_id, channel, recipient_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("tenantId") UUID tenantId, @Param("eventId") UUID eventId,
                       @Param("eventType") String eventType, @Param("type") String type,
                       @Param("category") String category, @Param("channel") String channel,
                       @Param("userId") UUID userId, @Param("recipientKey") String recipientKey,
                       @Param("address") String address, @Param("masked") String masked,
                       @Param("params") String params, @Param("status") String status,
                       @Param("skipReason") String skipReason, @Param("nextAttemptAt") Instant nextAttemptAt,
                       @Param("now") Instant now);
}
