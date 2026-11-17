package lms.sales.shared.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import lms.sales.shared.application.TenantDirectoryPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Danh bạ tenant (sales_tenants, KHÔNG RLS, không entity JPA vì bảng không thuộc tenant). {@code touch} chạy trong
 * transaction tạo đơn: INSERT ... ON CONFLICT DO NOTHING bằng JDBC cùng kết nối/transaction. Bảng chỉ có mã tenant và
 * thời điểm thấy lần đầu, không dữ liệu cá nhân.
 */
@Repository
class TenantDirectoryAdapter implements TenantDirectoryPort {

    private final JdbcTemplate jdbc;

    TenantDirectoryAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void touch(UUID tenantId) {
        jdbc.update("INSERT INTO sales_tenants (tenant_id) VALUES (?) ON CONFLICT (tenant_id) DO NOTHING", tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> listAll() {
        return jdbc.query("SELECT tenant_id FROM sales_tenants ORDER BY first_seen_at, tenant_id",
                (rs, i) -> rs.getObject(1, UUID.class));
    }
}
