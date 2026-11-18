package lms.support.notification.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Tenant đã từng có thông báo (bảng toàn cục không RLS, không @TenantId, nằm trong TENANT_EXEMPT_TABLES): dispatcher
 * lặp qua đây rồi chạy từng tenant trong TenantContext.runAs. Chỉ chứa id tenant, không có dữ liệu tenant.
 */
@Entity
@Table(name = "notification_tenants")
class NotificationTenantJpaEntity {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    protected NotificationTenantJpaEntity() {
    }

    UUID getTenantId() { return tenantId; }
}
