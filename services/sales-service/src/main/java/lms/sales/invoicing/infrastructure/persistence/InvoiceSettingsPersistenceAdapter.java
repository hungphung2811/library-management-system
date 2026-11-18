package lms.sales.invoicing.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lms.sales.invoicing.application.InvoiceSettingsPort;
import lms.sales.invoicing.domain.InvoiceSettings;
import lms.sales.shared.domain.ErrorCode;
import lms.sales.shared.domain.SalesException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lưu invoice_settings bằng JDBC (cùng transaction/kết nối với JPA, vẫn chịu RLS), giống {@code sales_settings}: khóa
 * chính là tenant_id trùng với cột {@code @TenantId} của Hibernate nên không ánh xạ thành entity JPA. Mọi câu lệnh có
 * tenant_id tường minh. Khóa lạc quan bằng UPDATE có điều kiện version: lần lưu đầu chèn version 1.
 */
@Repository
class InvoiceSettingsPersistenceAdapter implements InvoiceSettingsPort {

    private static final String COLUMNS = "tenant_id, seller_legal_name, seller_tax_code, seller_address, "
            + "seller_phone, seller_email, template_code, series, auto_issue_on_completion, version, updated_at, "
            + "updated_by";

    private final JdbcTemplate jdbc;

    InvoiceSettingsPersistenceAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InvoiceSettings> find(UUID tenantId) {
        return jdbc.query("select " + COLUMNS + " from invoice_settings where tenant_id = ?", (rs, i) ->
                InvoiceSettings.restore(rs.getObject("tenant_id", UUID.class), rs.getString("seller_legal_name"),
                        rs.getString("seller_tax_code"), rs.getString("seller_address"),
                        rs.getString("seller_phone"), rs.getString("seller_email"), rs.getString("template_code"),
                        rs.getString("series"), rs.getBoolean("auto_issue_on_completion"), rs.getLong("version"),
                        rs.getTimestamp("updated_at").toInstant(), rs.getObject("updated_by", UUID.class)),
                tenantId).stream().findFirst();
    }

    @Override
    @Transactional
    public InvoiceSettings save(InvoiceSettings s) {
        Timestamp at = Timestamp.from(s.updatedAt() == null ? Instant.now() : s.updatedAt());
        if (s.version() == 0) {
            try {
                jdbc.update("insert into invoice_settings (" + COLUMNS + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)",
                        s.tenantId(), s.sellerLegalName(), s.sellerTaxCode(), s.sellerAddress(), s.sellerPhone(),
                        s.sellerEmail(), s.templateCode(), s.series(), s.autoIssueOnCompletion(), at, s.updatedBy());
            } catch (DuplicateKeyException e) {
                throw conflict();
            }
        } else {
            int rows = jdbc.update("update invoice_settings set seller_legal_name = ?, seller_tax_code = ?, "
                            + "seller_address = ?, seller_phone = ?, seller_email = ?, template_code = ?, "
                            + "series = ?, auto_issue_on_completion = ?, updated_at = ?, updated_by = ?, "
                            + "version = version + 1 where tenant_id = ? and version = ?",
                    s.sellerLegalName(), s.sellerTaxCode(), s.sellerAddress(), s.sellerPhone(), s.sellerEmail(),
                    s.templateCode(), s.series(), s.autoIssueOnCompletion(), at, s.updatedBy(), s.tenantId(),
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
