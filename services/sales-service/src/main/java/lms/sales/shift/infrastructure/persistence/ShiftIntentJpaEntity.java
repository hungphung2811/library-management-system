package lms.sales.shift.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

/** Góc nhìn CHỈ ĐỌC của bảng payment_intents: đếm lệnh thu tay còn chờ của ca (chặn đóng ca). */
@Entity
@Immutable
@Table(name = "payment_intents")
class ShiftIntentJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "shift_id", updatable = false)
    private UUID shiftId;

    @Column(name = "status", nullable = false, updatable = false)
    private String status;

    @Column(name = "provider", nullable = false, updatable = false)
    private String provider;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    protected ShiftIntentJpaEntity() {
    }
}
