package lms.sales.shared.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * Bộ đếm số chứng từ theo tenant. Khóa chính ở DB là (tenant_id, name); ở JPA chỉ cần name vì mỗi phiên Hibernate
 * gắn một tenant (@TenantId lọc và điền tenant_id, RLS lọc thêm). Chỉ tăng bằng JPQL bulk update.
 */
@Entity
@Table(name = "sales_counters")
class SalesCounterJpaEntity {

    @Id
    @Column(name = "name", length = 30)
    private String name;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "last_value", nullable = false)
    private long lastValue;

    protected SalesCounterJpaEntity() {
    }
}
