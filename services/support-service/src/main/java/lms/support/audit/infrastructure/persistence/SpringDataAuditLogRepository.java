package lms.support.audit.infrastructure.persistence;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataAuditLogRepository
        extends JpaRepository<AuditLogJpaEntity, UUID>, JpaSpecificationExecutor<AuditLogJpaEntity> {

    /** INSERT ... ON CONFLICT DO NOTHING: idempotent và an toàn khi hai consumer xử lý trùng eventId cùng lúc. */
    @Modifying
    @Query(value = """
            INSERT INTO audit_logs (id, event_id, event_type, aggregate_id, occurred_at, recorded_at, payload)
            VALUES (:id, :eventId, :eventType, :aggregateId, :occurredAt, :recordedAt, :payload)
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("eventId") UUID eventId, @Param("eventType") String eventType,
                       @Param("aggregateId") String aggregateId, @Param("occurredAt") Instant occurredAt,
                       @Param("recordedAt") Instant recordedAt, @Param("payload") String payload);
}
