package lms.circulation.reservation.infrastructure.persistence;

import lms.circulation.reservation.domain.ReservationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * Đặt trước thuộc một tenant: tenant_id do Hibernate điền/lọc theo TenantContext (@TenantId), RLS lọc thêm ở DB.
 * Hàng đợi và các chỉ mục unique một phần (uq_reservations_active, uq_reservations_ready_copy) tính riêng từng tenant.
 */
@Entity
@Table(name = "reservations")
class ReservationJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "reader_id", nullable = false)
    private UUID readerId;

    @Column(name = "title_id", nullable = false)
    private UUID titleId;

    @Column(name = "reserved_at", nullable = false)
    private Instant reservedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    @Column(name = "copy_id")
    private UUID copyId;

    @Column(name = "hold_until")
    private LocalDate holdUntil;

    protected ReservationJpaEntity() {
    }

    ReservationJpaEntity(UUID id, UUID readerId, UUID titleId, Instant reservedAt, ReservationStatus status,
                         UUID copyId, LocalDate holdUntil) {
        this.id = id;
        this.readerId = readerId;
        this.titleId = titleId;
        this.reservedAt = reservedAt;
        this.status = status;
        this.copyId = copyId;
        this.holdUntil = holdUntil;
    }

    UUID getId() { return id; }
    UUID getReaderId() { return readerId; }
    UUID getTitleId() { return titleId; }
    Instant getReservedAt() { return reservedAt; }
    ReservationStatus getStatus() { return status; }
    UUID getCopyId() { return copyId; }
    LocalDate getHoldUntil() { return holdUntil; }
}
