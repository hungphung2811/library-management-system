package lms.sales.settings.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lms.sales.settings.application.SalesSettingsPort;
import lms.sales.settings.domain.SalesSettings;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lưu sales_settings bằng JDBC (cùng transaction/kết nối với JPA, vẫn chịu RLS): bảng có khóa chính duy nhất là
 * tenant_id, trùng với cột @TenantId của Hibernate nên không ánh xạ được thành entity JPA (insert ghi hai lần cột
 * tenant_id). Mọi câu lệnh có tenant_id tường minh. Khóa lạc quan bằng UPDATE có điều kiện version: lần lưu đầu chèn
 * version 1 (0 ở domain nghĩa là "chưa từng lưu"), hai người lưu cùng lúc thì đúng một người thành công.
 */
@Repository
class SalesSettingsPersistenceAdapter implements SalesSettingsPort {

    private static final String COLUMNS = "tenant_id, vat_rate_bps, prices_include_vat, return_window_days, "
            + "fixed_shipping_fee, low_stock_threshold, walk_in_pii_retention_months, version, updated_at, updated_by";

    private final JdbcTemplate jdbc;

    SalesSettingsPersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SalesSettings> find(UUID tenantId) {
        return jdbc.query("select " + COLUMNS + " from sales_settings where tenant_id = ?", (rs, i) ->
                SalesSettings.restore(rs.getObject("tenant_id", UUID.class), rs.getInt("vat_rate_bps"),
                        rs.getBoolean("prices_include_vat"), rs.getInt("return_window_days"),
                        rs.getLong("fixed_shipping_fee"), rs.getInt("low_stock_threshold"),
                        rs.getInt("walk_in_pii_retention_months"), rs.getLong("version"),
                        rs.getTimestamp("updated_at").toInstant(), rs.getObject("updated_by", UUID.class)),
                tenantId).stream().findFirst();
    }

    @Override
    @Transactional
    public SalesSettings save(SalesSettings s) {
        Timestamp at = Timestamp.from(s.updatedAt() == null ? Instant.now() : s.updatedAt());
        if (s.version() == 0) {
            try {
                jdbc.update("insert into sales_settings (" + COLUMNS + ") values (?, ?, ?, ?, ?, ?, ?, 1, ?, ?)",
                        s.tenantId(), s.vatRateBps(), s.pricesIncludeVat(), s.returnWindowDays(),
                        s.fixedShippingFee(), s.lowStockThreshold(), s.walkInPiiRetentionMonths(), at,
                        s.updatedBy());
            } catch (DuplicateKeyException e) {
                throw conflict();
            }
        } else {
            int rows = jdbc.update("update sales_settings set vat_rate_bps = ?, prices_include_vat = ?, "
                            + "return_window_days = ?, fixed_shipping_fee = ?, low_stock_threshold = ?, "
                            + "walk_in_pii_retention_months = ?, updated_at = ?, updated_by = ?, "
                            + "version = version + 1 where tenant_id = ? and version = ?",
                    s.vatRateBps(), s.pricesIncludeVat(), s.returnWindowDays(), s.fixedShippingFee(),
                    s.lowStockThreshold(), s.walkInPiiRetentionMonths(), at, s.updatedBy(), s.tenantId(),
                    s.version());
            if (rows != 1) {
                throw conflict();
            }
        }
        return find(s.tenantId()).orElseThrow();
    }

    private static SalesException conflict() {
        return new SalesException(ErrorCode.SETTINGS_VERSION_CONFLICT, "Cấu hình đã được người khác thay đổi");
    }
}
