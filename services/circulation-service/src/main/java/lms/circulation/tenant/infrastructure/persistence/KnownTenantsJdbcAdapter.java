package lms.circulation.tenant.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import lms.circulation.reservation.application.ActiveTenantsPort;
import lms.circulation.tenant.application.KnownTenantsPort;
import lms.circulation.tenant.domain.KnownTenantStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Bảng known_tenants (toàn cục, không RLS: việc nền đọc danh sách tenant trước khi có tenant trong ngữ cảnh). Đây là
 * bean DUY NHẤT cài {@link ActiveTenantsPort}: ExpireReservationsJob lấy qua ObjectProvider.getIfAvailable(), có hai
 * bean cùng kiểu thì ném lỗi.
 */
public class KnownTenantsJdbcAdapter implements KnownTenantsPort, ActiveTenantsPort {

    private final JdbcTemplate jdbc;

    public KnownTenantsJdbcAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(UUID tenantId, KnownTenantStatus status) {
        jdbc.update("INSERT INTO known_tenants (tenant_id, status, updated_at) VALUES (?, ?, now()) "
                + "ON CONFLICT (tenant_id) DO UPDATE SET status = EXCLUDED.status, updated_at = now()",
                tenantId, status.name());
    }

    @Override
    public List<UUID> activeTenantIds() {
        return jdbc.queryForList("SELECT tenant_id FROM known_tenants WHERE status = ? ORDER BY tenant_id", UUID.class,
                KnownTenantStatus.ACTIVE.name());
    }
}
