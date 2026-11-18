package lms.sales.shift.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;

/** Góc nhìn CHỈ ĐỌC của bảng sales_orders: tổng đơn và giảm giá của các đơn có tiền vào ca. */
@Entity
@Immutable
@Table(name = "sales_orders")
class ShiftOrderJpaEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "grand_total", nullable = false, updatable = false)
    private long grandTotal;

    @Column(name = "discount_total", nullable = false, updatable = false)
    private long discountTotal;

    protected ShiftOrderJpaEntity() {
    }
}
