package lms.identity.tenantdata.infrastructure.persistence;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import lms.identity.tenantdata.application.CsvWriter;
import lms.identity.tenantdata.application.ExportFailure;
import lms.identity.tenantdata.application.ExportSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import lms.tenant.TenantContext;

/**
 * Phần dữ liệu của chính identity trong gói xuất (ADR-0031 D2/phụ lục A): danh sách bảng và cột CỐ ĐỊNH ở đây, không dump
 * schema thô. Loại có chủ đích: mật khẩu băm (credentials), refresh token, token đặt lại mật khẩu, mã xác minh email,
 * vé đăng nhập ngoài, liên kết đăng nhập ngoài, secret/hint của credential Application, site key và ghi chú nội bộ của
 * hợp đồng. Mọi truy vấn lọc
 * {@code tenant_id} tường minh (bảng toàn cục) và chạy trong {@code TenantContext} + transaction (bảng RLS), đọc theo con
 * trỏ có fetch size cố định (không nạp cả bảng vào RAM).
 */
public class IdentityTenantDataSource implements ExportSource {

    static final int SCHEMA_VERSION = 1;
    /** Fetch size cố định của JdbcTemplate dành riêng cho xuất (cấu hình ở TenantDataBeanConfiguration). */
    public static final int FETCH_SIZE = 500;

    /** Bảng xuất: tên, câu SQL (tham số {@code :t} = tenant) đã sắp xếp ổn định. */
    record Table(String name, String sql) {
    }

    static final List<Table> TABLES = List.of(
            new Table("users", "SELECT u.id, u.username, u.full_name, u.email, u.status, u.phone, u.job_title, "
                    + "u.employee_code, u.created_at FROM users u JOIN tenant_memberships m ON m.user_id = u.id "
                    + "WHERE m.tenant_id = :t ORDER BY u.id"),
            new Table("tenant_memberships", "SELECT user_id, department_id, status, joined_at FROM tenant_memberships "
                    + "WHERE tenant_id = :t ORDER BY user_id"),
            new Table("roles", "SELECT id, name, description, is_admin, signup_default FROM roles "
                    + "WHERE tenant_id = :t ORDER BY id"),
            new Table("role_permissions", "SELECT rp.role_id, rp.permission_code FROM role_permissions rp "
                    + "JOIN roles r ON r.id = rp.role_id WHERE r.tenant_id = :t ORDER BY rp.role_id, rp.permission_code"),
            new Table("user_roles", "SELECT user_id, role_id FROM user_roles WHERE tenant_id = :t "
                    + "ORDER BY user_id, role_id"),
            new Table("departments", "SELECT id, code, name, parent_id, status FROM departments "
                    + "WHERE tenant_id = :t ORDER BY id"),
            new Table("reader_profiles", "SELECT user_id, card_no, full_name, email, reader_type, card_expiry_date, "
                    + "status, phone, date_of_birth, gender, address, faculty, class_name, member_code, "
                    + "card_issued_date FROM reader_profiles WHERE tenant_id = :t ORDER BY user_id"),
            new Table("applications", "SELECT id, name, kind, status, rate_limit_per_minute, created_at, created_by, "
                    + "updated_at, deleted_at FROM applications WHERE tenant_id = :t ORDER BY id"),
            new Table("tenant_contracts", "SELECT id, name, status, legacy, starts_at, ends_at, total_amount, "
                    + "billing_cycle_unit, billing_cycle_value, cancel_reason, created_at, updated_at "
                    + "FROM tenant_contracts WHERE tenant_id = :t ORDER BY id"));

    static final Map<String, String> EXCLUDED = Map.of(
            "credentials", "Mật khẩu băm và dữ liệu xác thực",
            "refresh_tokens", "Token làm mới phiên",
            "password_reset_tokens", "Token đặt lại mật khẩu",
            "email_verifications", "Mã xác minh email",
            "external_sign_in_tickets", "Vé đăng nhập ngoài",
            "external_logins", "Liên kết đăng nhập ngoài",
            "application_credentials", "Secret và băm secret của ứng dụng tích hợp",
            "applications.site_key", "Khóa công khai của website (site key)",
            "tenant_contracts.internal_note", "Ghi chú nội bộ của nhà cung cấp",
            "permissions", "Bảng toàn cục của nền tảng, không thuộc tenant");

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public IdentityTenantDataSource(NamedParameterJdbcTemplate jdbc, TransactionTemplate readOnlyTx) {
        this.jdbc = jdbc;
        this.tx = readOnlyTx;
    }

    @Override
    public String name() {
        return "identity";
    }

    @Override
    public void export(UUID tenantId, UUID exportId, TableSink sink) {
        sink.schemaVersion(SCHEMA_VERSION);
        try {
            TenantContext.callAs(tenantId, () -> tx.execute(status -> {
                for (Table t : TABLES) {
                    exportTable(t, tenantId, sink);
                }
                return null;
            }));
        } catch (ExportFailure | SourceFailure f) {
            throw f;
        } catch (UncheckedIOException e) {
            throw new SourceFailure("SOURCE_ERROR", e);
        } catch (DataAccessException e) {
            throw new SourceFailure("SOURCE_ERROR", e);
        }
        EXCLUDED.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> sink.excluded(e.getKey(), e.getValue()));
    }

    private void exportTable(Table t, UUID tenantId, TableSink sink) {
        String file = t.name() + ".csv";
        AtomicLong rows = new AtomicLong();
        List<String> columns = new ArrayList<>();
        try (OutputStream out = sink.openFile(file); CsvWriter csv = new CsvWriter(out)) {
            jdbc.query(t.sql(), new MapSqlParameterSource("t", tenantId), (ResultSet rs) -> {
                try {
                    if (columns.isEmpty()) {
                        ResultSetMetaData md = rs.getMetaData();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            columns.add(md.getColumnLabel(i));
                        }
                        csv.row(columns);
                    }
                    List<String> cells = new ArrayList<>(columns.size());
                    for (int i = 1; i <= columns.size(); i++) {
                        cells.add(format(rs.getObject(i)));
                    }
                    csv.row(cells);
                    rows.incrementAndGet();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                } catch (SQLException e) {
                    throw new org.springframework.jdbc.UncategorizedSQLException("export", t.sql(), e);
                }
            });
            if (columns.isEmpty()) {
                // bảng rỗng vẫn có dòng tiêu đề: lấy tên cột từ câu SQL qua truy vấn không dòng
                columns.addAll(columnsOf(t));
                csv.row(columns);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        sink.table(t.name(), file, rows.get(), List.copyOf(columns));
    }

    private List<String> columnsOf(Table t) {
        return jdbc.query("SELECT * FROM (" + t.sql().replaceAll("(?i)\\s+ORDER BY .*$", "") + ") q WHERE false",
                new MapSqlParameterSource("t", new UUID(0, 0)), (ResultSet rs) -> {
                    List<String> cols = new ArrayList<>();
                    ResultSetMetaData md = rs.getMetaData();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        cols.add(md.getColumnLabel(i));
                    }
                    return cols;
                });
    }

    static String format(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant().toString();
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant().toString();
        }
        if (value instanceof String text) {
            return neutralizeFormula(text);
        }
        return String.valueOf(value);
    }

    /**
     * Chống công thức bảng tính, cùng quy ước với các exporter khác (ADR-0031 phụ lục A, tenant-data-exporters.md): ô VĂN BẢN
     * bắt đầu bằng {@code = + - @}, TAB hoặc CR được thêm dấu nháy đơn phía trước; số, ngày, boolean, thời điểm không bị
     * đụng (số âm giữ nguyên vì không phải chuỗi).
     */
    static String neutralizeFormula(String text) {
        if (text.isEmpty()) {
            return text;
        }
        char c = text.charAt(0);
        return (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r') ? "'" + text : text;
    }
}
